package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.stoandlConfigDir
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** A pbw's PKJS app and its plugin sessions share one localStorage (same uuid scope). */
class JSLocalStorageTest {
    private val tempDir = Files.createTempDirectory("stoandl-localstorage")
    private val realHome = System.getProperty("user.home")
    private val uuid = Uuid.random().toString()

    @BeforeTest
    fun isolateConfigDir() {
        System.setProperty("user.home", tempDir.toString())
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", realHome)
        tempDir.toFile().deleteRecursively()
    }

    private fun pkjs() = GraalJSLocalStorageInterface(uuid, AppContext())
    private fun pluginSession() = JsEngineLocalStorage(uuid, AppContext(), eval = {})

    private fun storedOnDisk() = Properties().apply {
        File(File(stoandlConfigDir(), "pkjs"), "$uuid.properties").inputStream().use { load(it) }
    }

    @Test
    fun pkjsAndItsPluginKeepEachOthersKeys() {
        val app = pkjs()
        app.setItem("fromApp", "1")
        // A plugin session starts after the app and writes while the app is still running.
        val plugin = pluginSession()
        plugin.setItem("fromPlugin", "2")
        app.setItem("fromAppAgain", "3")

        assertEquals("2", app.getItem("fromPlugin"))
        assertEquals("3", plugin.getItem("fromAppAgain"))
        assertEquals(3, pluginSession().getLength())
        assertEquals(setOf("fromApp", "fromPlugin", "fromAppAgain"), storedOnDisk().stringPropertyNames())
    }

    @Test
    fun concurrentWritersLoseNothing() {
        val writers = (0 until 4).map { w ->
            val storage = if (w % 2 == 0) pkjs() else pluginSession()
            thread { repeat(50) { i -> storage.setItem("w$w-$i", "$i") } }
        }
        writers.forEach { it.join() }

        assertEquals(200, storedOnDisk().size)
    }
}
