package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * GraalJS, shaped like the iOS engine: a bare ECMAScript context whose only way out is one host
 * method, [JsEngineDispatcher.dispatch], behind a JS proxy per [JsEngineInterface]. Arguments and
 * results cross as JSON, as on Android, so every number arrives as a `Double`.
 *
 * Plugin sessions are cold-started per request, so contexts share one engine ([GraalEngines]: its
 * code caches make a warm start a few milliseconds); each engine instance has its own context and
 * JS thread.
 * Nothing else is exposed: no host class lookup, no `Java`/`Packages`, no shell builtins (`print`,
 * `load`, `Graal`). Like JavaScriptCore, there are no timers: `typeof setTimeout` is `undefined`.
 * `crypto.getRandomValues` is provided (WebView has it, and plugins otherwise fall back to
 * `Math.random` for OAuth state).
 */
actual class JsEngine actual constructor(
    appContext: AppContext,
    scope: CoroutineScope,
    private val name: String,
    private val interfaces: List<JsEngineInterface>,
) {
    private val logger = Logger.withTag("JsEngine-$name")

    // Interpreter-mode GraalJS recurses deeply; GraalJsRunner needs the same 4 MB.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "JsEngine-$name", 4L * 1024 * 1024).apply { isDaemon = true }
    }
    private val jsThread = executor.asCoroutineDispatcher()

    @Volatile private var context: Context? = null
    @Volatile private var stopped = false

    actual suspend fun start() {
        check(!stopped) { "JsEngine $name was stopped" }
        withContext(jsThread) { startOnJsThread() }
    }

    private fun startOnJsThread() {
        val ctx = GraalEngines.contextBuilder(DISPATCH_ONLY)
            .allowHostClassLookup { false }
            .option("js.print", "false")
            .option("js.load", "false")
            .option("js.graal-builtin", "false")
            // PLUGIN_HOST_JS brings a console that logs through the host; GraalJS's own writes to stdout.
            .option("js.console", "false")
            .build()
        context = ctx
        ctx.getBindings("js").putMember(DISPATCH_NAMESPACE, JsEngineDispatcher(interfaces + RandomSource, logger))
        ctx.eval("js", LOCKDOWN_JS)
        interfaces.forEach { ctx.eval("js", it.proxyJs()) }
    }

    actual suspend fun eval(js: String) {
        if (stopped || context == null) return
        withContext(jsThread) {
            val ctx = context ?: return@withContext
            try {
                ctx.eval("js", js)
            } catch (e: PolyglotException) {
                if (!e.isCancelled && !e.isInterrupted) logger.e(e) { "JS error: ${e.message}" }
            } catch (e: IllegalStateException) {
                // Closed under us by stop()'s cancel fallback.
                if (context != null) throw e
            }
        }
    }

    /**
     * Closes the context on the JS thread after whatever is already queued there; if a script is
     * still running after [STOP_TIMEOUT] (a plugin stuck in a loop), cancels it from here. The same
     * bounded close as [GraalJsRunner.stop], and NonCancellable for the same reason: callers stop
     * sessions from scopes that may already be cancelled.
     */
    actual suspend fun stop() {
        if (stopped) return
        stopped = true
        withContext(NonCancellable) {
            try {
                val closed = CompletableDeferred<Unit>()
                executor.execute { closed.completeWith(runCatching { closeContext(cancelIfExecuting = false) }) }
                if (withTimeoutOrNull(STOP_TIMEOUT) { closed.await() } == null) {
                    logger.w { "JS thread still busy after $STOP_TIMEOUT; cancelling the running script" }
                    closeContext(cancelIfExecuting = true)
                }
            } catch (e: Exception) {
                logger.e(e) { "Error closing JS context" }
            } finally {
                jsThread.close()
            }
        }
    }

    private fun closeContext(cancelIfExecuting: Boolean) {
        val ctx = synchronized(this) { context.also { context = null } } ?: return
        ctx.close(cancelIfExecuting)
    }

    private companion object {
        const val DISPATCH_NAMESPACE = "__jsEngineDispatch"
        val STOP_TIMEOUT = 5.seconds

        /** Exactly one host member is reachable from JS: [JsEngineDispatcher.dispatch]. */
        val DISPATCH_ONLY: HostAccess = HostAccess.newBuilder()
            .allowAccess(
                JsEngineDispatcher::class.java.getMethod(
                    "dispatch", String::class.java, String::class.java, String::class.java,
                )
            )
            .build()

        /**
         * A host error comes back as `{"e": message}` and is rethrown as a JS `Error`, which the
         * plugin can catch; the host exception object itself never reaches JS.
         */
        fun JsEngineInterface.proxyJs(): String {
            val methodList = methods.joinToString(",") { "'$it'" }
            return """
                (function (global) {
                  var dispatcher = global.$DISPATCH_NAMESPACE;
                  var target = {};
                  [$methodList].forEach(function (m) {
                    target[m] = function () {
                      var raw = dispatcher.dispatch(
                        '$name', m, JSON.stringify(Array.prototype.slice.call(arguments)));
                      if (raw === null || raw === undefined) return null;
                      var reply = JSON.parse(raw);
                      if ('e' in reply) throw new Error(reply.e);
                      return reply.v;
                    };
                  });
                  global['$name'] = target;
                })(globalThis);
            """.trimIndent()
        }

        /**
         * The polyglot globals GraalJS adds even with host access locked down. `Java.type` already
         * fails without host class lookup; removing them keeps the global scope what a plugin sees
         * on JavaScriptCore. Then `crypto.getRandomValues`, over [RandomSource].
         */
        val LOCKDOWN_JS = """
            (function (global) {
              ['Java', 'Packages', 'java', 'javax', 'javafx', 'com', 'org', 'edu', 'arguments']
                .forEach(function (key) { delete global[key]; });
              var dispatcher = global.$DISPATCH_NAMESPACE;
              function getRandomValues(array) {
                if (!(ArrayBuffer.isView(array)) || array instanceof Float32Array ||
                    array instanceof Float64Array || array instanceof DataView) {
                  throw new TypeError('getRandomValues needs an integer typed array');
                }
                if (array.byteLength > 65536) {
                  throw new RangeError('getRandomValues: at most 65536 bytes');
                }
                var hex = JSON.parse(dispatcher.dispatch(
                  '${RandomSource.name}', 'hex', JSON.stringify([array.byteLength]))).v;
                var bytes = new Uint8Array(array.buffer, array.byteOffset, array.byteLength);
                for (var i = 0; i < bytes.length; i++) bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
                return array;
              }
              Object.defineProperty(global, 'crypto', {
                value: Object.freeze({ getRandomValues: getRandomValues }),
                configurable: true,
              });
            })(globalThis);
        """.trimIndent()
    }
}

/** Backs `crypto.getRandomValues`; reachable only through the lockdown shim (no proxy global). */
private object RandomSource : JsEngineInterface {
    private val random = SecureRandom()
    override val name = "__engineRandom"
    override val methods = listOf("hex")

    @OptIn(ExperimentalStdlibApi::class)
    override fun dispatch(method: String, args: List<Any?>): Any? {
        val size = (args.single() as Number).toInt()
        require(size in 0..65536) { "bad size $size" }
        return ByteArray(size).also(random::nextBytes).toHexString()
    }
}

/**
 * The only host object a [JsEngine] context holds, and [dispatch] its only member JS may call.
 * Public (to Graal's reflection) only because host access needs it; `internal` to Kotlin.
 */
internal class JsEngineDispatcher(
    interfaces: List<JsEngineInterface>,
    private val logger: Logger,
) {
    private val byName = interfaces.associateBy { it.name }

    /** Null for a null result; otherwise `{"v": result}` or `{"e": message}`. */
    fun dispatch(name: String, method: String, argsJson: String): String? {
        val result = try {
            val iface = byName[name] ?: error("Unknown interface: $name")
            val args = Json.decodeFromString(JsonArray.serializer(), argsJson).map { it.toKotlin() }
            iface.dispatch(method, args) ?: return null
        } catch (e: Exception) {
            logger.w { "$name.$method failed: $e" }
            return buildJsonObject { put("e", JsonPrimitive(e.message ?: e::class.simpleName)) }.toString()
        }
        return buildJsonObject { put("v", result.toJson()) }.toString()
    }

    // As Android's dispatcher: numbers as Double, objects and arrays as their JSON text.
    private fun JsonElement.toKotlin(): Any? = when {
        this is JsonNull -> null
        this is JsonPrimitive && isString -> content
        this is JsonPrimitive -> booleanOrNull ?: doubleOrNull ?: content
        else -> toString()
    }

    private fun Any.toJson(): JsonPrimitive = when (this) {
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }
}
