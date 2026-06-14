package io.rebble.libpebblecommon.js

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.stoandlConfigDir
import java.io.File
import java.util.Properties

internal actual fun createJSSettings(appContext: AppContext, id: String): Settings {
    val dir = File(stoandlConfigDir(), "pkjs")
    dir.mkdirs()
    val file = File(dir, "$id.properties")
    val props = Properties()
    if (file.exists()) file.inputStream().use { props.load(it) }
    return PropertiesSettings(props) { p -> file.outputStream().use { p.store(it, null) } }
}
