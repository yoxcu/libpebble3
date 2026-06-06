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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

class RhinoJsRunner(
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

    // 8 MB stack: Rhino's regex engine recurses deeply for complex patterns;
    // the default JVM thread stack (512 KB on Linux) overflows on some watchapp config pages.
    private val jsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "JSRunner-${appInfo.uuid}", 8 * 1024 * 1024)
    }
    @OptIn(DelicateCoroutinesApi::class)
    private val jsThread = jsExecutor.asCoroutineDispatcher()
    private val jsScope = scope + jsThread
    private var rhinoScope: Scriptable? = null
    private val logger = Logger.withTag("RhinoJsRunner-${appInfo.longName}")

    // evalFn callable from non-suspend contexts (XHR/timeout callbacks that have already
    // switched to jsThread via withContext).
    private val evalFn: (String) -> Unit = { js ->
        val scope = rhinoScope
        if (scope != null) {
            val cx = RhinoContext.enter()
            cx.languageVersion = RhinoContext.VERSION_ES6
            cx.optimizationLevel = -1
            try {
                cx.evaluateString(scope, js, "<callback>", 1, null)
            } catch (e: Throwable) {
                logger.e(e) { "JS callback error: ${e.message}" }
            } finally {
                RhinoContext.exit()
            }
        }
    }

    override suspend fun start() {
        withContext(jsThread) {
            val cx = RhinoContext.enter()
            cx.languageVersion = RhinoContext.VERSION_ES6
            cx.optimizationLevel = -1  // interpreter mode: avoids classloader issues
            val scope = cx.initStandardObjects()
            rhinoScope = scope

            val xhrManager = JvmXMLHTTPRequestManager(jsScope, jsThread, evalFn, appInfo)
            val timeoutManager = JvmJSTimeout(jsScope, jsThread, evalFn)
            val pkjsIface = JvmPKJSInterface(this@RhinoJsRunner, device, libPebble, jsTokenUtil)
            val privatePkjsIface = JvmPrivatePKJSInterface(
                this@RhinoJsRunner, device, jsScope,
                _outgoingAppMessages, logMessages, jsTokenUtil,
                remoteTimelineEmulator, httpInterceptorManager, notificationConfigFlow,
            )

            fun put(name: String, obj: Any) {
                ScriptableObject.putProperty(scope, name, RhinoContext.javaToJS(obj, scope))
            }

            val localStorage = RhinoJSLocalStorageInterface(appInfo.uuid, appContext)

            put("_pebblePublicNative", pkjsIface)
            put("_Pebble", privatePkjsIface)
            put("_XMLHTTPRequestManager", xhrManager)
            put("_Timeout", timeoutManager)
            put("_PebbleGeo", GeolocationStub)
            put("localStorage", localStorage)

            // Bootstrap: console stub + navigator + Pebble JS wrapper
            cx.evaluateString(scope, BOOTSTRAP_JS, "<bootstrap>", 1, null)

            evalResource(cx, scope, "/pkjs/JSTimeout.js")
            evalResource(cx, scope, "/pkjs/XMLHTTPRequest.js")
            evalResource(cx, scope, "/pkjs/startup.js")

            RhinoContext.exit()
        }
        loadAppJs(jsPath.toString())
    }

    private fun evalResource(cx: RhinoContext, scope: Scriptable, resourcePath: String) {
        val content = RhinoJsRunner::class.java.getResourceAsStream(resourcePath)?.bufferedReader()?.readText()
            ?: error("JS resource not found: $resourcePath")
        cx.evaluateString(scope, content, resourcePath, 1, null)
    }

    override suspend fun loadAppJs(jsUrl: String) {
        withContext(jsThread) {
            val content = SystemFileSystem.source(Path(jsUrl)).buffered().use { it.readString() }
            val scope = rhinoScope ?: return@withContext
            val cx = RhinoContext.enter()
            cx.languageVersion = RhinoContext.VERSION_ES6
            cx.optimizationLevel = -1
            try {
                cx.evaluateString(scope, content, "${appInfo.uuid}.js", 1, null)
            } catch (e: Throwable) {
                logger.e(e) { "Error loading app JS: ${e.message}" }
            } finally {
                RhinoContext.exit()
            }
        }
        signalReady()
    }

    override suspend fun eval(js: String) {
        withContext(jsThread) {
            val scope = rhinoScope ?: return@withContext
            val cx = RhinoContext.enter()
            cx.languageVersion = RhinoContext.VERSION_ES6
            cx.optimizationLevel = -1
            try {
                cx.evaluateString(scope, js, "<eval>", 1, null)
            } catch (e: Throwable) {
                logger.e(e) { "JS eval error: ${e.message}" }
            } finally {
                RhinoContext.exit()
            }
        }
    }

    override suspend fun evalWithResult(js: String): Any? = withContext(jsThread) {
        val scope = rhinoScope ?: return@withContext null
        val cx = RhinoContext.enter()
        cx.languageVersion = RhinoContext.VERSION_ES6
        cx.optimizationLevel = -1
        try {
            cx.evaluateString(scope, js, "<eval>", 1, null)
        } catch (e: Throwable) {
            logger.e(e) { "evalWithResult error: ${e.message}" }
            null
        } finally {
            RhinoContext.exit()
        }
    }

    private fun jsStringArg(s: String?): String =
        if (s != null) "'${s.replace("\\", "\\\\").replace("'", "\\'")}'" else "null"

    override suspend fun signalNewAppMessageData(data: String?): Boolean {
        eval("signalNewAppMessageData(${jsStringArg(data)})")
        return true
    }

    override suspend fun signalReady() = eval("signalReady()")
    override suspend fun signalShowConfiguration() = eval("signalShowConfiguration()")

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

    override fun debugForceGC() { /* Rhino does not expose GC control */ }

    override suspend fun stop() {
        withContext(jsThread) {
            rhinoScope = null
        }
        jsThread.close()
        jsExecutor.shutdown()
    }
}

private object GeolocationStub {
    @JvmField val unsupported = true
    @JvmStatic fun getRequestCallbackID(): Int = 0
    @JvmStatic fun getWatchCallbackID(): Int = 0
    @JvmStatic fun getCurrentPosition(id: Int, maxAge: Double, timeout: Double, highAccuracy: Int) {}
    @JvmStatic fun watchPosition(id: Int, interval: Double, highAccuracy: Int): Int = 0
    @JvmStatic fun clearWatch(id: Int) {}
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
