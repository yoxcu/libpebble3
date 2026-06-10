package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PlatformIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope

// JVM/Linux drives BLE entirely over BlueZ D-Bus and never touches kable/btleplug — its native lib
// (libbtleplug_ffi.so) is glibc-only and fails to load on musl (e.g. postmarketOS). So no kable
// peripheral is ever created on this target.
actual fun peripheralFromIdentifier(
    identifier: PebbleBleIdentifier,
    name: String,
    autoConnect: Boolean,
): Peripheral? = null

// Never null here: a null identifier aborts the connection and makes WatchManager forget a known
// watch. No kable peripheral; BlueZ ignores autoConnect (it manages reconnection itself).
actual fun createBlePlatformIdentifier(
    identifier: PebbleBleIdentifier,
    name: String,
    autoConnect: Boolean,
): PlatformIdentifier.BlePlatformIdentifier? =
    PlatformIdentifier.BlePlatformIdentifier(peripheral = null, autoConnect = autoConnect)

actual fun platformGattConnector(
    identifier: PebbleBleIdentifier,
    blePlatformIdentifier: PlatformIdentifier.BlePlatformIdentifier,
    scope: ConnectionCoroutineScope,
    blePlatformConfig: BlePlatformConfig,
): GattConnector = BluezGattConnector(identifier, scope)

// Unreachable on JVM (no kable peripheral): the BlueZ path answers MTU via
// BluezConnectedGattClient.requestMtu()/getMtu(). Kept from the old kable/btleplug backend, where
// echoing back the requested value caused a "Can't reduce MTU" crash when getMtu() subsequently
// returned the real (smaller) value, so report the actual negotiated ATT MTU instead.
actual suspend fun Peripheral.requestMtuNative(mtu: Int): Int =
    maximumWriteValueLengthForType(WriteType.WithoutResponse) + 3

// Unreachable on JVM (no kable peripheral): the BlueZ path uses BluezConnectedGattClient's own
// ConnectedGattClient.refreshServicesNative() override.
actual suspend fun Peripheral.refreshServicesNative(): Boolean = false
