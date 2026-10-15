package io.rebble.libpebblecommon.plugin

import kotlinx.coroutines.flow.Flow

/**
 * The phone's battery as the host reads it (stoandl: UPower), for [PhoneStatePlugin]. A cold flow
 * of 0..100, emitting the current value on collection; null when there is no battery to report.
 */
actual class PhoneBatteryMonitor(
    actual val batteryLevel: Flow<Int?>,
)
