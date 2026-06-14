package io.rebble.libpebblecommon.util

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.io.files.Path
import java.io.File

// Callers build names like "logs-${identifier.asString}", and on the JVM/BlueZ transport
// identifier.asString is a JSON blob (e.g. {"object_path":"/org/bluez/hci0/dev_AA_BB_..."}) whose
// '/' and '{}"' would otherwise spawn phantom nested directories and break the file open. Reduce each
// path component to a safe single segment, and make sure the temp directory exists before returning
// the path (the kotlinx-io sink does not create missing parents).
actual fun getTempFilePath(appContext: AppContext, name: String, subdir: String?): Path {
    val base = File(System.getProperty("java.io.tmpdir"), "stoandl")
    val dir = if (subdir != null) File(base, sanitizeSegment(subdir)) else base
    dir.mkdirs()
    return Path(dir.path, sanitizeSegment(name))
}

private fun sanitizeSegment(s: String): String =
    s.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "_" }
