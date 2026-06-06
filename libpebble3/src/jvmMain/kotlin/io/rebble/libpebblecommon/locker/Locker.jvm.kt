package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.io.files.Path

actual fun getLockerPBWCacheDirectory(context: AppContext): Path =
    Path(System.getProperty("user.home"), ".config", "stoandl", "pbw-cache")

actual fun getLockerPBWCacheLegacyDirectory(context: AppContext): Path? = null
