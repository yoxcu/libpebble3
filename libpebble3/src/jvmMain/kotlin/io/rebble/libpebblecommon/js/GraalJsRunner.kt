package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import org.graalvm.polyglot.Context as GraalContext
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException

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
                .allowHostAccess(HostAccess.ALL)
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
                remoteTimelineEmulator, httpInterceptorManager, notificationConfigFlow,
            )
            val localStorage = GraalJSLocalStorageInterface(appInfo.uuid, appContext)

            bindings.putMember("_pebblePublicNative", pkjsIface)
            bindings.putMember("_Pebble", privatePkjsIface)
            bindings.putMember("_XMLHTTPRequestManager", xhrManager)
            bindings.putMember("_Timeout", timeoutManager)
            bindings.putMember("_PebbleGeo", GraalGeolocationInterface(jsScope, this@GraalJsRunner))
            bindings.putMember("localStorage", localStorage)

            ctx.eval("js", BOOTSTRAP_JS)
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

    private fun jsStringArg(s: String?): String =
        if (s != null) "'${s.replace("\\", "\\\\").replace("'", "\\'")}'" else "null"

    override suspend fun signalNewAppMessageData(data: String?): Boolean {
        eval("signalNewAppMessageData(${jsStringArg(data)})")
        return true
    }

    override suspend fun signalReady() = eval("signalReady()")

    override suspend fun signalWebviewClosed(data: String?) =
        eval("signalWebviewClosedEvent(${jsStringArg(data)})")

    override suspend fun signalInterceptResponse(callbackId: String, result: InterceptResponse) {
        val body = result.result.replace("\\", "\\\\").replace("'", "\\'")
        eval("signalInterceptResponse({callbackId:'$callbackId',status:${result.status},response:'$body'})")
    }

    override suspend fun signalTimelineToken(callId: String, token: String) {
        val json = """{"callId":"${callId.replace("\"", "\\\"")}","userToken":"${token.replace("\"", "\\\"")}"}"""
        eval("signalTimelineTokenSuccess(${jsStringArg(json)})")
    }

    override suspend fun signalTimelineTokenFail(callId: String) {
        val json = """{"callId":"${callId.replace("\"", "\\\"")}"}"""
        eval("signalTimelineTokenFailure(${jsStringArg(json)})")
    }

    override fun debugForceGC() { /* GraalJS does not expose GC control */ }

    override suspend fun stop() {
        withContext(jsThread) {
            jsContext?.close()
            jsContext = null
        }
        jsThread.close()
        jsExecutor.shutdown()
    }
}

/**
 * JVM `navigator.geolocation` bridge, symmetric with Android's [WebViewGeolocationInterface] and
 * iOS's `JSCGeolocationInterface`: the shared [GeolocationInterface] does all the work (callback-id
 * bookkeeping, permission check, dispatching results back into the JS context via `_PebbleGeoCB`),
 * delegating the actual fix to the Koin-injected `SystemGeolocation`. On JVM that binding is a no-op
 * by default — a host (e.g. stoandl) overrides it with a real provider (GeoClue) to make this live.
 *
 * GraalJS exposes the public `open` methods directly to JS, so no per-method annotations are needed
 * (unlike Android's `@JavascriptInterface`).
 */
class GraalGeolocationInterface(
    scope: CoroutineScope,
    jsRunner: JsRunner,
) : GeolocationInterface(scope, jsRunner)

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
