package io.rebble.libpebblecommon.database.entity

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.ValueParams
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting.Companion.toJson
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Fork (stoandl): PebbleOS 4.39.0 registers the Quick Launch "Toggle Touch" action only on CONFIG_TOUCH
 * boards (obelix, getafix). Any other watch reads the UUID as an uninstalled app, and before 4.40 a tap
 * on that button reboots it, so the setting must not reach such a watch.
 */
class QuickLaunchActionSupportTest {
    private fun params(type: WatchType, tag: String) = ValueParams(
        platform = type,
        capabilities = emptySet(),
        firmwareVersion = FirmwareVersion.from(
            tag = tag,
            isRecovery = false,
            gitHash = "",
            timestamp = Instant.DISTANT_PAST,
            isDualSlot = false,
            isSlot0 = false,
        )!!,
        libPebbleConfigFlow = LibPebbleConfigFlow(MutableStateFlow(LibPebbleConfig())),
    )

    private fun item(pref: QuicklaunchWatchPref, enabled: Boolean, uuid: Uuid?) = WatchPrefItem(
        id = pref.id,
        value = QuickLaunchSetting(enabled, uuid).toJson(),
        timestamp = Instant.fromEpochSeconds(1760745600).asMillisecond(),
    )

    @Test
    fun toggleTouchIsTheFirmwaresUuid() {
        assertEquals(Uuid.parse("5cc5a4e8-150d-4ec3-9afc-965e3fdec3c3"), SystemAppIDs.TOUCH_TOGGLE_UUID)
        val support = assertNotNull(QuickLaunchActionSupport.of(SystemAppIDs.TOUCH_TOGGLE_UUID))
        assertEquals(WatchPrefMinFirmware(4, 39), support.minFirmware)
        assertEquals(setOf(WatchType.EMERY, WatchType.GABBRO), support.watchTypes)
    }

    @Test
    fun toggleTouchGoesOnlyToATouchWatchOn439() {
        val ql = item(QuicklaunchWatchPref.QlSingleClickUp, true, SystemAppIDs.TOUCH_TOGGLE_UUID)
        assertNotNull(ql.value(params(WatchType.EMERY, "v4.39.0")))
        assertNotNull(ql.value(params(WatchType.GABBRO, "v4.41.2")))
        assertNull(ql.value(params(WatchType.EMERY, "v4.38.2")))
        assertNull(ql.value(params(WatchType.FLINT, "v4.41.2")))
        assertNull(ql.value(params(WatchType.BASALT, "v4.4.2")))
    }

    @Test
    fun otherActionsAndAppsGoEverywhere() {
        assertNull(QuickLaunchActionSupport.of(SystemAppIDs.QUIET_TIME_TOGGLE_UUID))
        assertNull(QuickLaunchActionSupport.of(null))
        val flint = params(WatchType.FLINT, "v4.41.2")
        assertNotNull(item(QuicklaunchWatchPref.QlUp, true, SystemAppIDs.BACKLIGHT_UUID).value(flint))
        assertNotNull(item(QuicklaunchWatchPref.QlUp, true, SystemAppIDs.MUSIC_APP_UUID).value(flint))
        // A disabled button runs nothing, whatever UUID it remembers.
        assertNotNull(item(QuicklaunchWatchPref.QlUp, false, SystemAppIDs.TOUCH_TOGGLE_UUID).value(flint))
    }
}
