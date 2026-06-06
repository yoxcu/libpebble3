package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LibPebble

class JvmPKJSInterface(
    jsRunner: JsRunner,
    device: CompanionAppDevice,
    libPebble: LibPebble,
    jsTokenUtil: JsTokenUtil,
) : PKJSInterface(jsRunner, device, libPebble, jsTokenUtil) {
    private val logger = Logger.withTag("JvmPKJS-${jsRunner.appInfo.longName}")

    override fun showToast(toast: String) {
        logger.i { "[PKJS] $toast" }
    }
}
