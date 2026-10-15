package io.rebble.libpebblecommon.js

import com.sun.net.httpserver.HttpServer
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.FakeAppMessages
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.PebbleConnectionEvent
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.Watches
import io.rebble.libpebblecommon.connection.fakeWatch
import io.rebble.libpebblecommon.database.dao.LockerAppPermissionDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.stub
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.metadata.pbw.appinfo.Resources
import io.rebble.libpebblecommon.permissions.AppPermissions
import io.rebble.libpebblecommon.permissions.InstalledApps
import io.rebble.libpebblecommon.permissions.PermissionSubject
import io.rebble.libpebblecommon.permissions.PluginDefinedPermission
import io.rebble.libpebblecommon.permissions.RunningApps
import io.rebble.libpebblecommon.plugin.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.io.path.writeText
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * [GraalJsRunner] on the engine that ships it, with the real JVM shims (startup.js,
 * XMLHTTPRequest.js, JSTimeout.js): the signal/reply round trips watchapp JS depends on, and the
 * host-access allow-list that keeps that JS inside the PKJS API. Nothing else runs PKJS offline;
 * checking the shims with another engine (node) says nothing about GraalJS.
 */
class GraalJsRunnerTest {
    private val config = MutableStateFlow(LibPebbleConfig())
    private val watchConfig = WatchConfigFlow(config)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logMessages = Channel<String>(Channel.UNLIMITED)
    private val runners = mutableListOf<GraalJsRunner>()
    private val tempDir = Files.createTempDirectory("stoandl-pkjs")
    private val realHome = System.getProperty("user.home")

    // Answers intercept.test the way the remote-timeline emulator answers the timeline API.
    private val interceptor = object : HttpInterceptor {
        override fun shouldIntercept(url: String) = url.startsWith("https://intercept.test/")
        override suspend fun onIntercepted(url: String, method: String, body: String?, appUuid: Uuid) =
            InterceptResponse(result = "intercepted $method $body", status = 200)
    }

    // localStorage lives under stoandlConfigDir(): keep it off the real ~/.config/stoandl (a set
    // XDG_CONFIG_HOME still wins; these tests only create the directory, they never store items).
    @BeforeTest
    fun isolateConfigDir() {
        System.setProperty("user.home", tempDir.toString())
    }

    @AfterTest
    fun tearDown() {
        runBlocking { runners.forEach { it.stop() } }
        scope.cancel()
        System.setProperty("user.home", realHome)
        tempDir.toFile().deleteRecursively()
    }

    private fun runner(appJs: String, legacy: Boolean = true): GraalJsRunner {
        val uuid = Uuid.random()
        val permissions = testPermissions(watchConfig, legacy)
        val jsFile = tempDir.resolve("$uuid.js").apply { writeText(appJs) }
        val remoteTimeline = RemoteTimelineEmulator(watchConfig, Json, stub(), stub(), permissions)
        val watch = fakeWatch(connected = true) as ConnectedPebbleDevice
        return GraalJsRunner(
            appContext = AppContext(),
            libPebble = FakeLibPebble(),
            jsTokenUtil = JsTokenUtil(stub(), stub(), watchConfig, permissions),
            device = CompanionAppDevice(watch.identifier, watch.watchInfo, FakeAppMessages()),
            scope = scope,
            appInfo = testAppInfo(uuid),
            lockerEntry = testLockerEntry(uuid),
            jsPath = Path(jsFile.toString()),
            urlOpenRequests = Channel(Channel.UNLIMITED),
            logMessages = logMessages,
            remoteTimelineEmulator = remoteTimeline,
            httpInterceptorManager = HttpInterceptorManager(remoteTimeline, InjectedPKJSHttpInterceptors(listOf(interceptor))),
            notificationConfigFlow = NotificationConfigFlow(config),
            pluginRegistry = PluginRegistry(emptySet(), watchConfig),
            permissions = permissions,
            runningApps = RunningApps(NoWatches),
        ).also { runners += it }
    }

    /** Polls [expression] until it is neither null nor undefined. */
    private suspend fun JsRunner.awaitJs(expression: String): Any = withTimeout(TIMEOUT) {
        flow {
            while (true) {
                emit(evalWithResult(expression))
                delay(20)
            }
        }.filterNotNull().first()
    }

    @Test
    fun bridgeInitializesAndConfirmsReady() = runBlocking<Unit> {
        val runner = runner("Pebble.addEventListener('ready', function() { globalThis.sawReady = true; });")
        runner.start()
        withTimeout(TIMEOUT) { runner.readyState.first { it } }
        assertEquals(true, runner.evalWithResult("globalThis.sawReady === true"))
        // console.log reaches the host through _Pebble.onConsoleLog.
        withTimeout(TIMEOUT) { logMessages.receiveAsFlow().first { it.endsWith("Pebble JS Bridge initialized.") } }
    }

    @Test
    fun hostObjectsExposeOnlyThePkjsApi() = runBlocking<Unit> {
        val runner = runner("")
        runner.start()
        assertEquals("function", runner.evalWithResult("typeof _Pebble.sendAppMessageString"))
        assertEquals("function", runner.evalWithResult("typeof _XMLHTTPRequestManager.send"))
        assertEquals("function", runner.evalWithResult("typeof localStorage.getItem"))
        assertEquals(0L, runner.evalWithResult("localStorage.length"))
        // getClass() leads to the class loader and from there to reflection and Runtime.exec().
        val bound = listOf("_Pebble", "_pebblePublicNative", "_XMLHTTPRequestManager", "_Timeout", "_PebbleGeo", "localStorage")
        for (name in bound) {
            assertEquals("undefined", runner.evalWithResult("typeof $name.getClass"), "$name.getClass")
        }
        assertEquals(
            "blocked",
            runner.evalWithResult("try { _Pebble.getClass().getClassLoader(); 'escaped' } catch (e) { 'blocked' }"),
        )
        // Public, but not PKJS API: the DI graph, and the runner's own teardown.
        assertEquals("undefined", runner.evalWithResult("typeof _PebbleGeo.getKoin"))
        assertEquals("undefined", runner.evalWithResult("typeof _XMLHTTPRequestManager.cancelAll"))
    }

    @Test
    fun appMessageAndConfigMessageRoundTrip() = runBlocking<Unit> {
        val runner = runner(
            """
            Pebble.addEventListener('appmessage', function(e) { globalThis.lastPayload = e.payload; });
            Pebble.addEventListener('configmessage', function(e) { e.respond({ echo: e.data }); });
            """.trimIndent()
        )
        runner.start()
        // Quotes, a newline and a line separator: JSON-encoded on the way in, never hand-escaped.
        val tricky = "a'b\"c\nd\u2028e"
        runner.signalNewAppMessageData(buildJsonObject { put("k", tricky) }.toString())
        assertEquals(tricky, runner.evalWithResult("globalThis.lastPayload.k"))

        val reply = assertNotNull(runner.sendConfigMessage(buildJsonObject { put("x", tricky) }.toString()))
        assertEquals(
            buildJsonObject { put("echo", buildJsonObject { put("x", tricky) }) },
            Json.parseToJsonElement(reply),
        )
    }

    @Test
    fun interceptedXhrCompletes() = runBlocking<Unit> {
        val runner = runner(
            """
            var xhr = new XMLHttpRequest();
            xhr.onload = function() { globalThis.intercepted = xhr.status + ' ' + xhr.responseText; };
            xhr.open('POST', 'https://intercept.test/v1/user/pins');
            xhr.send('{"id":1}');
            """.trimIndent()
        )
        runner.start()
        assertEquals("200 intercepted POST {\"id\":1}", runner.awaitJs("globalThis.intercepted"))
    }

    @Test
    fun xhrCanBeReused() = runBlocking<Unit> {
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                requests += "${exchange.requestURI.path} ${exchange.requestHeaders.getFirst("X-Test")}"
                val body = "reply ${requests.size}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val runner = runner(
                """
                var received = [];
                var xhr = new XMLHttpRequest();
                xhr.onload = function() {
                    received.push(xhr.responseText);
                    if (received.length === 1) {
                        xhr.open('GET', '$base/second');
                        xhr.send();
                    } else {
                        globalThis.replies = received.join();
                    }
                };
                xhr.open('GET', '$base/first');
                xhr.setRequestHeader('X-Test', '1');
                xhr.send();
                """.trimIndent()
            )
            runner.start()
            assertEquals("reply 1,reply 2", runner.awaitJs("globalThis.replies"))
            // open() starts afresh: the first request's header doesn't carry over.
            assertEquals(listOf("/first 1", "/second null"), requests.toList())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun xhrAcceptsAndDecodesGzip() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                if (exchange.requestHeaders.getFirst("Accept-Encoding")?.contains("gzip") != true) {
                    exchange.sendResponseHeaders(400, -1)
                    exchange.close()
                    return@createContext
                }
                val body = ByteArrayOutputStream().also { out ->
                    GZIPOutputStream(out).use { it.write("{\"radar\":[]}".toByteArray()) }
                }.toByteArray()
                exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val runner = runner(
                """
                var xhr = new XMLHttpRequest();
                xhr.onload = function() { globalThis.reply = xhr.status + ' ' + xhr.responseText; };
                xhr.open('GET', 'http://127.0.0.1:${server.address.port}/radar');
                xhr.send();
                """.trimIndent()
            )
            runner.start()
            assertEquals("200 {\"radar\":[]}", runner.awaitJs("globalThis.reply"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun xhrObeysTheNetworkPermissionOncePluginsAreEnabled() = runBlocking<Unit> {
        config.value = LibPebbleConfig(watchConfig = WatchConfig(enablePlugins = true))
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                requests += exchange.requestURI.path
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("ok".toByteArray()) }
            }
            start()
        }
        try {
            fun app(path: String) = """
                var xhr = new XMLHttpRequest();
                xhr.onload = function() { globalThis.outcome = 'loaded ' + xhr.responseText; };
                xhr.onerror = function() { globalThis.outcome = 'refused'; };
                xhr.open('GET', 'http://127.0.0.1:${server.address.port}/$path');
                xhr.send();
            """.trimIndent()
            // A manifest-v2 app must be granted LocalNetwork first; nobody has answered yet.
            val v2 = runner(app("v2"), legacy = false)
            v2.start()
            assertEquals("refused", v2.awaitJs("globalThis.outcome"))
            // A legacy app keeps its implicit GlobalInternet.
            val legacy = runner(app("legacy"))
            legacy.start()
            assertEquals("loaded ok", legacy.awaitJs("globalThis.outcome"))
            assertEquals(listOf("/legacy"), requests.toList())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun consoleTraceDoesNotThrow() = runBlocking<Unit> {
        val runner = runner("console.trace('traced'); globalThis.afterTrace = true;")
        runner.start()
        assertEquals(true, runner.evalWithResult("globalThis.afterTrace === true"))
    }

    @Test
    fun stopEndsTheJsThread() = runBlocking<Unit> {
        val runner = runner("")
        runner.start()
        val threadName = "JSRunner-${runner.appInfo.uuid}"
        fun jsThreadAlive() = Thread.getAllStackTraces().keys.any { it.name == threadName && it.isAlive }
        assertTrue(jsThreadAlive(), "no $threadName thread")
        runners -= runner
        runner.stop()
        withTimeout(TIMEOUT) { while (jsThreadAlive()) delay(20) }
    }

    private companion object {
        // Generous: a GraalJS context in interpreter mode takes a while to warm up on a busy host.
        val TIMEOUT = 20.seconds
    }
}

/** No watch is ever connected. */
internal object NoWatches : Watches {
    override val watches = MutableStateFlow(emptyList<PebbleDevice>())
    override val connectionEvents = emptyFlow<PebbleConnectionEvent>()
    override fun watchesDebugState() = ""
}

/**
 * A real [AppPermissions] over an empty permission table: every app is installed, as a legacy
 * (pre-manifest-v2) app unless [legacy] is false, and permissions are enforced only when
 * [watchConfig] enables plugins.
 */
internal fun testPermissions(watchConfig: WatchConfigFlow, legacy: Boolean = true): AppPermissions {
    val dao = Proxy.newProxyInstance(
        LockerAppPermissionDao::class.java.classLoader,
        arrayOf(LockerAppPermissionDao::class.java),
    ) { _, m, _ ->
        when (m.name) {
            "getByAppUuid" -> emptyList<Any>()
            "insertIfAbsent" -> Unit // a use or a pending request, recorded once enforcing
            else -> throw UnsupportedOperationException(m.name)
        }
    } as LockerAppPermissionDao
    val installed = object : InstalledApps {
        override fun subjectsOf(appUuids: Set<Uuid>) =
            flowOf(appUuids.associateWith { PermissionSubject(it, legacy = legacy) })
        override fun pluginPermissions() = flowOf(emptyMap<String, PluginDefinedPermission>())
    }
    return AppPermissions(dao, installed, watchConfig, Clock.System, RunningApps(NoWatches),
        LibPebbleCoroutineScope(EmptyCoroutineContext))
}

internal fun testAppInfo(uuid: Uuid) = PbwAppInfo(
    uuid = uuid.toString(),
    shortName = "Test App",
    longName = "Test App",
    versionLabel = "1.0",
    resources = Resources(emptyList()),
)

internal fun testLockerEntry(uuid: Uuid) = LockerEntry(
    id = uuid,
    version = "1.0",
    title = "Test App",
    type = "watchapp",
    developerName = "Test Developer",
    configurable = false,
    pbwVersionCode = "1",
    platforms = emptyList(),
)
