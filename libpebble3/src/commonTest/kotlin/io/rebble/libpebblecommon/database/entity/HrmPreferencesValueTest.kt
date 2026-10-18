package io.rebble.libpebblecommon.database.entity

import io.rebble.libpebblecommon.database.entity.HrmPreferencesValue.Companion.asBytes
import io.rebble.libpebblecommon.database.entity.HrmPreferencesValue.Companion.encodeToString
import io.rebble.libpebblecommon.health.HealthSettings
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

private fun fw(major: Int, minor: Int, patch: Int) = FirmwareVersion(
    stringVersion = "v$major.$minor.$patch",
    timestamp = Instant.DISTANT_PAST,
    major = major,
    minor = minor,
    patch = patch,
    suffix = null,
    gitHash = "",
    isRecovery = false,
    isDualSlot = false,
    isSlot0 = false,
)

private val FW_4_39 = fw(4, 39, 0)

private fun settings(interval: HRMonitoringInterval, enabled: Boolean = true) = HealthSettings(
    heightMm = 1700, weightDag = 7000, trackingEnabled = true, activityInsightsEnabled = false,
    sleepInsightsEnabled = false, ageYears = 35, gender = HealthGender.Other, imperialUnits = false,
    hrmEnabled = enabled, hrmMeasurementInterval = interval, hrmActivityTrackingEnabled = false,
    restingHr = 70, elevatedHr = 100, maxHr = 190, hrZone1Threshold = 130, hrZone2Threshold = 154,
    hrZone3Threshold = 172,
)

/** ActivityHRMSettings (`enabled`, `measurement_interval`, `activity_tracking_enabled`) both ways. */
class HrmPreferencesValueTest {

    @Test
    fun `interval codes match the firmware enum`() {
        // include/pbl/services/activity/activity.h: 10Min = 0, 30Min, 1Hour, Disabled, 5Min (4.39.0)
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            listOf(
                HRMonitoringInterval.TenMin, HRMonitoringInterval.ThirtyMin, HRMonitoringInterval.OneHour,
                HRMonitoringInterval.Disabled, HRMonitoringInterval.FiveMin,
            ).map { it.value.toInt() },
        )
        assertEquals(HRMonitoringInterval.FiveMin, HRMonitoringInterval.fromInt(4))
        assertNull(HRMonitoringInterval.fromInt(5))
        assertNull(HRMonitoringInterval.fromInt(-1))
    }

    @Test
    fun `five minutes from the watch decodes and goes back unchanged`() {
        val v = HrmPreferencesValue.fromWatchBytes(ubyteArrayOf(1u, 4u, 1u), previous = null)
        assertEquals(HrmPreferencesValue(true, HRMonitoringInterval.FiveMin, true), v)
        assertContentEquals(ubyteArrayOf(1u, 4u, 1u), v.asBytes(FW_4_39))
        assertEquals(v, HrmPreferencesValue.fromString(v.encodeToString()))
    }

    @Test
    fun `an interval code we can't name is kept, not thrown on`() {
        val previous = HrmPreferencesValue(measurementInterval = HRMonitoringInterval.ThirtyMin)
        val v = HrmPreferencesValue.fromWatchBytes(ubyteArrayOf(1u, 9u, 0u), previous)
        assertEquals(HRMonitoringInterval.ThirtyMin, v.measurementInterval)
        assertEquals(9.toByte(), v.unknownMeasurementInterval)
        assertContentEquals(ubyteArrayOf(1u, 9u, 0u), v.asBytes(FW_4_39))
        assertEquals(v, HrmPreferencesValue.fromString(v.encodeToString()))

        val noPrevious = HrmPreferencesValue.fromWatchBytes(ubyteArrayOf(0u, 9u, 0u), previous = null)
        assertEquals(HrmPreferencesValue().measurementInterval, noPrevious.measurementInterval)
        assertEquals(9.toByte(), noPrevious.unknownMeasurementInterval)
    }

    @Test
    fun `a named code from the watch replaces the previous interval and drops an unknown one`() {
        val previous = HrmPreferencesValue(
            measurementInterval = HRMonitoringInterval.ThirtyMin, unknownMeasurementInterval = 9,
        )
        val v = HrmPreferencesValue.fromWatchBytes(ubyteArrayOf(1u, 2u, 0u), previous)
        assertEquals(HrmPreferencesValue(true, HRMonitoringInterval.OneHour, false), v)
    }

    @Test
    fun `old firmware without the interval field decodes to the defaults`() {
        val v = HrmPreferencesValue.fromWatchBytes(ubyteArrayOf(1u), previous = null)
        assertEquals(HrmPreferencesValue(enabled = true), v)
        assertContentEquals(ubyteArrayOf(1u), v.asBytes(fw(4, 9, 140)))
    }

    @Test
    fun `rows stored before the unknown field still decode`() {
        val v = HrmPreferencesValue.fromString(
            """{"enabled":true,"measurementInterval":"OneHour","activityTrackingEnabled":true}"""
        )
        assertEquals(HrmPreferencesValue(true, HRMonitoringInterval.OneHour, true), v)
    }

    @Test
    fun `an unknown code survives other edits until a different interval is picked`() {
        val previous = HrmPreferencesValue(
            measurementInterval = HRMonitoringInterval.ThirtyMin, unknownMeasurementInterval = 9,
        )
        val hrmOff = HrmPreferencesValue.from(settings(HRMonitoringInterval.ThirtyMin, enabled = false), previous)
        assertEquals(9.toByte(), hrmOff.unknownMeasurementInterval)
        assertContentEquals(ubyteArrayOf(0u, 9u, 0u), hrmOff.asBytes(FW_4_39))

        val picked = HrmPreferencesValue.from(settings(HRMonitoringInterval.FiveMin), previous)
        assertNull(picked.unknownMeasurementInterval)
        assertContentEquals(ubyteArrayOf(1u, 4u, 0u), picked.asBytes(FW_4_39))

        assertNull(HrmPreferencesValue.from(settings(HRMonitoringInterval.TenMin), previous = null).unknownMeasurementInterval)
    }
}
