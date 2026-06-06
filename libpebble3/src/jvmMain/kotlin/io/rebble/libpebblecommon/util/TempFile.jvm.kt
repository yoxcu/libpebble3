package io.rebble.libpebblecommon.util

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.io.files.Path

actual fun getTempFilePath(appContext: AppContext, name: String, subdir: String?): Path {
    val base = Path(System.getProperty("java.io.tmpdir"), "stoandl")
    return if (subdir != null) Path(base.toString(), subdir, name) else Path(base.toString(), name)
}
