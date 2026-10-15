package io.rebble.libpebblecommon.js

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.stoandlConfigDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * One live [Settings] per store, shared by everything that opens it — as Android's
 * SharedPreferences are. A pbw's PKJS app and its plugin sessions use the same id; with a snapshot
 * each, every write saved the writer's whole snapshot and so dropped the other side's keys.
 */
private val jsSettings = ConcurrentHashMap<File, Settings>()

internal actual fun createJSSettings(appContext: AppContext, id: String): Settings {
    val file = File(File(stoandlConfigDir(), "pkjs"), "$id.properties").absoluteFile
    return jsSettings.computeIfAbsent(file) { open(it) }
}

private fun open(file: File): Settings {
    file.parentFile.mkdirs()
    val props = Properties()
    if (file.exists()) file.inputStream().use { props.load(it) }
    // The JS threads of a PKJS app and of its plugin sessions write concurrently: one save at a time,
    // each complete before it replaces the file.
    return PropertiesSettings(props) { p ->
        synchronized(p) {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.outputStream().use { p.store(it, null) }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
