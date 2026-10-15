package io.rebble.libpebblecommon.js

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.di.stub
import io.rebble.libpebblecommon.permissions.NetworkAccess
import io.rebble.libpebblecommon.permissions.RunningApps
import io.rebble.libpebblecommon.plugin.JsPlugin
import io.rebble.libpebblecommon.plugin.PluginManifest
import io.rebble.libpebblecommon.plugin.PluginNetworkVerdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The JVM [JsEngine] under upstream's real plugin host ([JsPlugin]: PLUGIN_HOST_JS, FETCH_JS,
 * localStorage, the XHR manager) with an unmodified example plugin from `test-apps/`, and the
 * engine's own guarantees: one host method, JSON-shaped arguments, no way out to the JVM.
 */
class JsEngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tempDir = Files.createTempDirectory("stoandl-jsengine")
    private val realHome = System.getProperty("user.home")
    private val engines = mutableListOf<JsEngine>()
    private val lenientJson = Json { ignoreUnknownKeys = true }

    // localStorage lives under stoandlConfigDir(); keep it off the real ~/.config/stoandl.
    @BeforeTest
    fun isolateConfigDir() {
        System.setProperty("user.home", tempDir.toString())
    }

    @AfterTest
    fun tearDown() {
        runBlocking { engines.forEach { it.stop() } }
        scope.cancel()
        System.setProperty("user.home", realHome)
        tempDir.toFile().deleteRecursively()
    }

    /** Records what JS hands the host, and answers `echo` with its first argument. */
    private class Probe : JsEngineInterface {
        val calls = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
        val reported = CompletableDeferred<List<Any?>>()
        override val name = "probe"
        override val methods = listOf("report", "echo", "fail")
        override fun dispatch(method: String, args: List<Any?>): Any? {
            calls += method to args
            return when (method) {
                "report" -> { reported.complete(args); null }
                "echo" -> args.firstOrNull()
                else -> error("host refused")
            }
        }
    }

    private suspend fun engineWith(vararg interfaces: JsEngineInterface, name: String = "test") =
        JsEngine(AppContext(), scope, name, interfaces.toList()).also {
            engines += it
            it.start()
        }

    @Test
    fun stocksPluginServesItsSource() = runBlocking<Unit> {
        // The plugin as upstream ships it, not a copy: whatever it does next, this runs it.
        val app = File("../test-apps/stocks")
        val manifest = lenientJson.decodeFromString(
            PluginManifest.serializer(),
            Json.parseToJsonElement(File(app, "package.json").readText())
                .jsonObject["pebble"]!!.jsonObject["plugin"].toString(),
        )
        val requested = CopyOnWriteArrayList<String>()
        val yahoo = HttpClient(MockEngine { request ->
            requested += request.url.toString()
            respond(
                """{"chart":{"result":[{"meta":{"symbol":"^GSPC","shortName":"S&P 500","currency":"USD",""" +
                    """"regularMarketPrice":5123.4,"previousClose":5100}}]}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { install(WebSockets) }
        val config = WatchConfigFlow(MutableStateFlow(LibPebbleConfig()))
        val timeline = RemoteTimelineEmulator(config, Json, stub(), stub(), testPermissions(config))
        val plugin = JsPlugin(
            pluginUuid = Uuid.random(),
            name = "Stocks",
            manifest = manifest,
            script = { File(app, manifest.script).readText() },
            appContext = AppContext(),
            scope = scope,
            httpClient = yahoo,
            httpInterceptorManager = HttpInterceptorManager(timeline, InjectedPKJSHttpInterceptors(emptyList())),
            networkAccess = NetworkAccess { _, _ -> PluginNetworkVerdict.Allowed },
            runningApps = RunningApps(NoWatches),
        )

        val envelope = withTimeout(TIMEOUT) { plugin.observe("finance", "stock", null, null).first() }

        assertEquals(listOf("^GSPC"), envelope.instances.map { it.instanceId })
        val price = envelope.instances.single().properties.getValue("price")
        assertEquals("$5123.40  +0.46%", price.getValue("longText").jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(requested.single().startsWith("https://query1.finance.yahoo.com/v8/finance/chart/%5EGSPC"))
    }

    @Test
    fun onlyTheDispatcherIsReachable() = runBlocking<Unit> {
        val probe = Probe()
        val engine = engineWith(probe)
        engine.eval(
            """
            function attempt(f) { try { return String(f()); } catch (e) { return 'blocked'; } }
            probe.report(
              typeof __jsEngineDispatch.getClass,
              typeof __jsEngineDispatch.hashCode,
              attempt(function () { return __jsEngineDispatch.getClass().getClassLoader(); }),
              typeof Java,
              attempt(function () { return Java.type('java.lang.Runtime'); }),
              typeof Packages, typeof print, typeof load, typeof Graal, typeof Polyglot,
              typeof setTimeout
            );
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "undefined", "undefined", "blocked", "undefined", "blocked",
                "undefined", "undefined", "undefined", "undefined", "undefined", "undefined",
            ),
            withTimeout(TIMEOUT) { probe.reported.await() },
        )
    }

    @Test
    fun argumentsAndResultsCrossAsJson() = runBlocking<Unit> {
        val probe = Probe()
        val engine = engineWith(probe)
        engine.eval(
            """
            var caught;
            try { probe.fail(); } catch (e) { caught = e instanceof Error ? e.message : 'not an Error'; }
            probe.report(1, 2.5, 'two', true, null, { a: [1] }, probe.echo(3) + 1, probe.echo('s'), caught);
            """.trimIndent()
        )
        assertEquals(
            listOf(1.0, 2.5, "two", true, null, """{"a":[1]}""", 4.0, "s", "host refused"),
            withTimeout(TIMEOUT) { probe.reported.await() },
        )
    }

    @Test
    fun cryptoFillsFromSecureRandom() = runBlocking<Unit> {
        val probe = Probe()
        val engine = engineWith(probe)
        engine.eval(
            """
            var a = new Uint8Array(32), b = new Uint32Array(4);
            var same = crypto.getRandomValues(a) === a;
            crypto.getRandomValues(b);
            var tooBig;
            try { crypto.getRandomValues(new Uint8Array(65537)); } catch (e) { tooBig = e.name; }
            probe.report(same, Array.prototype.some.call(a, function (x) { return x !== 0; }),
              Array.prototype.some.call(b, function (x) { return x !== 0; }), tooBig);
            """.trimIndent()
        )
        assertEquals(listOf(true, true, true, "RangeError"), withTimeout(TIMEOUT) { probe.reported.await() })
    }

    @Test
    fun stopCancelsAStuckScript() = runBlocking<Unit> {
        val engine = engineWith(name = "stuck")
        val looping = scope.async { engine.eval("while (true) {}") }
        delay(200)
        withTimeout(TIMEOUT) { engine.stop() }
        withTimeout(TIMEOUT) { looping.await() }
        fun alive() = Thread.getAllStackTraces().keys.any { it.name == "JsEngine-stuck" && it.isAlive }
        withTimeout(TIMEOUT) { while (alive()) delay(20) }
    }

    private companion object {
        // Generous: a cold GraalJS engine in interpreter mode on a busy host.
        val TIMEOUT = 20.seconds
    }
}
