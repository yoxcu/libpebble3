package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.stoandlConfigDir
import kotlinx.io.files.Path

actual fun getLockerPBWCacheDirectory(context: AppContext): Path =
    Path(stoandlConfigDir().absolutePath, "pbw-cache")

actual fun getLockerPBWCacheLegacyDirectory(context: AppContext): Path? = null
