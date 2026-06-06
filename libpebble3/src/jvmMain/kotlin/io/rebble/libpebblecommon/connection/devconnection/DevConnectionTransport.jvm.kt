package io.rebble.libpebblecommon.connection.devconnection

import kotlinx.io.files.Path

internal actual fun getTempPbwPath(): Path =
    Path(System.getProperty("java.io.tmpdir"), "stoandl-dev-pbw.pbw")
