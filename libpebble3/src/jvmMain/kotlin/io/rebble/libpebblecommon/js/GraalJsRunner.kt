package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.plugin.PluginRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import org.graalvm.polyglot.Context as GraalContext
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import java.lang.reflect.Modifier

class GraalJsRunner(
    private val appContext: AppContext,
    private val libPebble: LibPebble,
    private val jsTokenUtil: JsTokenUtil,
    device: CompanionAppDevice,
    private val scope: CoroutineScope,
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    jsPath: Path,
    urlOpenRequests: Channel<String>,
    private val logMessages: Channel<String>,
    private val remoteTimelineEmulator: RemoteTimelineEmulator,
    private val httpInterceptorManager: HttpInterceptorManager,
    private val notificationConfigFlow: NotificationConfigFlow,
    private val pluginRegistry: PluginRegistry,
) : JsRunner(appInfo, lockerEntry, jsPath, device, urlOpenRequests) {

    private val jsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "JSRunner-${appInfo.uuid}", 4 * 1024 * 1024)
    }
    @OptIn(DelicateCoroutinesApi::class)
    private val jsThread = jsExecutor.asCoroutineDispatcher()
    private val jsScope = scope + jsThread

    @Volatile private var jsContext: GraalContext? = null
    private val logger = Logger.withTag("GraalJsRunner-${appInfo.longName}")

    private val evalFn: (String) -> Unit = { js ->
        try {
            jsContext?.eval("js", js)
        } catch (e: PolyglotException) {
            if (!e.isInterrupted) logger.e(e) { "JS callback error: ${e.message}" }
        }
    }

    override suspend fun start() {
        withContext(jsThread) {
            val ctx = GraalContext.newBuilder("js")
                .allowHostAccess(PKJS_HOST_ACCESS)
                .allowHostClassLookup { _ -> false }
                .build()
            jsContext = ctx

            val bindings = ctx.getBindings("js")
            val xhrManager = JvmXMLHTTPRequestManager(jsScope, jsThread, evalFn, appInfo)
            val timeoutManager = JvmJSTimeout(jsScope, jsThread, evalFn)
            val pkjsIface = JvmPKJSInterface(this@GraalJsRunner, device, libPebble, jsTokenUtil)
            val privatePkjsIface = JvmPrivatePKJSInterface(
                this@GraalJsRunner, device, jsScope,
                _outgoingAppMessages, logMessages, jsTokenUtil,
                remoteTimelineEmulator, httpInterceptorManager, notificationConfigFlow, pluginRegistry,
            )
            val localStorage = GraalJSLocalStorageInterface(appInfo.uuid, appContext)

            bindings.putMember("_pebblePublicNative", pkjsIface)
            bindings.putMember("_Pebble", privatePkjsIface)
            bindings.putMember("_XMLHTTPRequestManager", xhrManager)
            bindings.putMember("_Timeout", timeoutManager)
            bindings.putMember("_PebbleGeo", GraalGeolocationInterface(jsScope, this@GraalJsRunner))
            bindings.putMember("localStorage", localStorage)

            ctx.eval("js", BOOTSTRAP_JS)
            // atob/btoa: browser APIs GraalJS lacks, as on iOS (plugin sources hand bitmaps over as base64).
            ctx.eval("js", BASE64_JS)
            evalResource(ctx, "/pkjs/JSTimeout.js")
            evalResource(ctx, "/pkjs/XMLHTTPRequest.js")
            evalResource(ctx, "/pkjs/startup.js")
        }
        loadAppJs(jsPath.toString())
    }

    private fun evalResource(ctx: GraalContext, resourcePath: String) {
        val content = GraalJsRunner::class.java.getResourceAsStream(resourcePath)?.bufferedReader()?.readText()
            ?: error("JS resource not found: $resourcePath")
        ctx.eval("js", content)
    }

    override suspend fun loadAppJs(jsUrl: String) {
        withContext(jsThread) {
            val ctx = jsContext ?: return@withContext
            val content = SystemFileSystem.source(Path(jsUrl)).buffered().use { it.readString() }
            try {
                ctx.eval("js", content)
            } catch (e: PolyglotException) {
                logger.e(e) { "Error loading app JS: ${e.message}" }
            }
        }
        signalReady()
    }

    override suspend fun eval(js: String) {
        withContext(jsThread) {
            val ctx = jsContext ?: return@withContext
            try {
                ctx.eval("js", js)
            } catch (e: PolyglotException) {
                if (e.isInterrupted) logger.i { "JS execution interrupted" }
                else logger.e(e) { "JS eval error: ${e.message}" }
            }
        }
    }

    override suspend fun signalShowConfiguration() {
        val ctx = jsContext ?: return
        val watchdog = scope.launch {
            delay(30_000)
            logger.w { "signalShowConfiguration taking >30s, interrupting JS" }
            try { ctx.interrupt(java.time.Duration.ofSeconds(5)) } catch (_: Exception) {}
        }
        try {
            eval("signalShowConfiguration()")
        } finally {
            watchdog.cancel()
        }
    }

    override suspend fun evalWithResult(js: String): Any? = withContext(jsThread) {
        val ctx = jsContext ?: return@withContext null
        try {
            val v = ctx.eval("js", js)
            when {
                v.isNull -> null
                v.isString -> v.asString()
                v.isBoolean -> v.asBoolean()
                v.fitsInLong() -> v.asLong()
                v.isNumber -> v.asDouble()
                else -> v
            }
        } catch (e: PolyglotException) {
            logger.e(e) { "evalWithResult error: ${e.message}" }
            null
        }
    }

    /**
     * [s] as a JS string literal (or `null`). JSON-encoded, never hand-escaped: a raw newline or
     * control char inside a hand-quoted literal is a silent SyntaxError and the callback never fires.
     */
    private fun jsStringArg(s: String?): String = Json.encodeToString(s)

    override suspend fun signalNewAppMessageData(data: String?): Boolean {
        eval("signalNewAppMessageData(${jsStringArg(data)})")
        return true
    }

    override suspend fun signalReady() = eval("signalReady()")

    override suspend fun signalWebviewClosed(data: String?) =
        eval("signalWebviewClosedEvent(${jsStringArg(data)})")

    override suspend fun signalInterceptResponse(callbackId: String, result: InterceptResponse) {
        val json = buildJsonObject {
            put("callbackId", callbackId)
            put("response", result.result)
            put("status", result.status)
        }
        // A raw object literal, not a quoted string: startup.js reads the fields directly.
        eval("signalInterceptResponse($json)")
    }

    override suspend fun signalTimelineToken(callId: String, token: String) {
        val json = buildJsonObject {
            put("callId", callId)
            put("userToken", token)
        }
        eval("signalTimelineTokenSuccess(${jsStringArg(json.toString())})")
    }

    override suspend fun signalTimelineTokenFail(callId: String) {
        val json = buildJsonObject { put("callId", callId) }
        eval("signalTimelineTokenFailure(${jsStringArg(json.toString())})")
    }

    /**
     * [json] is already JSON; it goes through [jsStringArg] + `JSON.parse` rather than being
     * spliced in raw (as Android/iOS do), so a malformed payload is a parse error, not code.
     */
    override suspend fun signalConfigMessage(requestId: Int, json: String) =
        eval("signalConfigMessageEvent($requestId, JSON.parse(${jsStringArg(json)}))")

    override fun debugForceGC() { /* GraalJS does not expose GC control */ }

    override suspend fun stop() {
        _readyState.value = false
        // NonCancellable, as on Android: stop() is often called from an already-cancelled
        // connection scope (watch disconnect), and withContext would then throw before closing
        // the context, leaking it and its non-daemon JS thread.
        withContext(NonCancellable) {
            try {
                // Close on the JS thread, after any callback already queued there. Awaited through a
                // deferred, not withContext(jsThread), which waits out a busy JS thread whatever the
                // timeout; bounded, since app JS stuck in a synchronous loop would otherwise hold this
                // NonCancellable stop() (and the app switch or disconnect behind it) forever.
                val closedOnJsThread = CompletableDeferred<Unit>()
                jsExecutor.execute {
                    closedOnJsThread.completeWith(runCatching { closeContext(cancelIfExecuting = false) })
                }
                if (withTimeoutOrNull(STOP_TIMEOUT) { closedOnJsThread.await() } == null) {
                    logger.w { "JS thread still busy after $STOP_TIMEOUT; cancelling the running script" }
                    closeContext(cancelIfExecuting = true)
                }
            } catch (e: Exception) {
                logger.e(e) { "Error closing Graal context" }
            } finally {
                // Always, even if closing failed: the non-daemon JS thread must not outlive the app.
                jsThread.close()
                jsExecutor.shutdown()
            }
        }
    }

    /**
     * Takes the context exactly once, so the JS-thread close and the timeout fallback never both
     * close it. `close(cancelIfExecuting = true)` may be called from any thread.
     */
    private fun closeContext(cancelIfExecuting: Boolean) {
        val ctx = synchronized(this) { jsContext.also { jsContext = null } } ?: return
        ctx.close(cancelIfExecuting)
    }

    private companion object {
        val STOP_TIMEOUT = 5.seconds
    }
}

/**
 * JVM `navigator.geolocation` bridge, symmetric with Android's [WebViewGeolocationInterface] and
 * iOS's `JSCGeolocationInterface`: the shared [GeolocationInterface] does all the work (callback-id
 * bookkeeping, permission check, dispatching results back into the JS context via `_PebbleGeoCB`),
 * delegating the actual fix to the Koin-injected `SystemGeolocation`. On JVM that binding is a no-op
 * by default — a host (e.g. stoandl) overrides it with a real provider (GeoClue) to make this live.
 *
 * Which of its methods JS may call is listed in [PKJS_HOST_API] (Android's `@JavascriptInterface`).
 */
class GraalGeolocationInterface(
    scope: CoroutineScope,
    jsRunner: JsRunner,
) : GeolocationInterface(scope, jsRunner)

/**
 * The host members PKJS may use on each object [GraalJsRunner.start] binds: exactly what the JVM
 * shims (BOOTSTRAP_JS, startup.js, XMLHTTPRequest.js, JSTimeout.js) call, plus the Storage API app
 * JS calls on `localStorage` directly. The JVM counterpart of Android's `@JavascriptInterface` and
 * iOS's interface maps. `HostAccess.ALL` exposed every public member instead, `getClass()` included,
 * and through it the class loader and reflection (and `_PebbleGeo.getKoin()`, the whole DI graph):
 * any watchapp's JS could run arbitrary code as the daemon's user.
 */
private val PKJS_HOST_API: Map<Class<*>, Set<String>> = mapOf(
    JvmPKJSInterface::class.java to setOf(
        "showSimpleNotificationOnPebble", "getAccountToken", "getWatchToken", "openURL", "showToast",
    ),
    JvmPrivatePKJSInterface::class.java to setOf(
        "onConsoleLog", "onError", "onUnhandledRejection", "privateFnConfirmReadySignal",
        "sendAppMessageString", "getTimelineTokenAsync", "getActivePebbleWatchInfo",
        "insertTimelinePin", "deleteTimelinePin", "subscribeToSource", "unsubscribeSource",
        "enumeratePlugins", "invokeAction", "configMessageReply", "sendConfigMessage",
        "shouldIntercept", "onIntercepted",
    ),
    JvmXMLHTTPRequestManager::class.java to setOf("getXHRInstanceID", "open", "setRequestHeader", "send", "abort"),
    JvmJSTimeout::class.java to setOf("setTimeout", "clearTimeout", "setInterval", "clearInterval"),
    GraalGeolocationInterface::class.java to setOf(
        "getRequestCallbackID", "getWatchCallbackID", "getCurrentPosition", "watchPosition", "clearWatch",
    ),
    GraalJSLocalStorageInterface::class.java to setOf("getItem", "setItem", "removeItem", "clear", "key"),
)

private val PKJS_HOST_ACCESS: HostAccess by lazy {
    val builder = HostAccess.newBuilder()
    PKJS_HOST_API.forEach { (type, names) ->
        val methods = type.methods.filter { it.name in names && !Modifier.isStatic(it.modifiers) }
        // Fail at the first PKJS start, not with a silent "not a function" in some app's JS.
        val missing = names - methods.map { it.name }.toSet()
        check(missing.isEmpty()) { "PKJS host API: ${type.simpleName} has no public $missing" }
        methods.forEach { builder.allowAccess(it) }
    }
    // Storage.length, kept current by JSLocalStorageInterface.setLength().
    builder.allowAccess(GraalJSLocalStorageInterface::class.java.getField("length"))
    builder.build()
}

private val BOOTSTRAP_JS = """
var console = {
    log:function(){},warn:function(){},error:function(){},
    info:function(){},debug:function(){},trace:function(){},assert:function(){}
};
var navigator = { language: 'en-US', geolocation: {} };
var Pebble = {
    showSimpleNotificationOnPebble: function(t,n) { _pebblePublicNative.showSimpleNotificationOnPebble(t,n); },
    getAccountToken: function() { return _pebblePublicNative.getAccountToken(); },
    getWatchToken:   function() { return _pebblePublicNative.getWatchToken(); },
    openURL:         function(url) { return _pebblePublicNative.openURL(url); },
    showToast:       function(toast) { _pebblePublicNative.showToast(toast); }
};
""".trimIndent()
