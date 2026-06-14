package io.rebble.libpebblecommon.web

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.stoandlConfigDir
import kotlinx.io.files.Path

actual fun getFirmwareDownloadDirectory(context: AppContext): Path =
    Path(stoandlConfigDir().absolutePath, "firmware")
