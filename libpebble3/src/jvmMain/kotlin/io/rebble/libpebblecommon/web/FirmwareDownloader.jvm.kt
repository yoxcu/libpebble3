package io.rebble.libpebblecommon.web

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.io.files.Path

actual fun getFirmwareDownloadDirectory(context: AppContext): Path =
    Path(System.getProperty("user.home"), ".local", "share", "gravel", "firmware")
