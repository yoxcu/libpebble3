package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.AppContext

class GraalJSLocalStorageInterface(
    scopedSettingsUuid: String,
    appContext: AppContext,
) : JSLocalStorageInterface(scopedSettingsUuid, appContext) {
    @JvmField var length: Int = getLength()

    override fun setLength(value: Int) {
        length = value
    }
}
