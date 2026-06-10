package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PlatformIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope

// JVM/Linux drives BLE entirely over BlueZ D-Bus and never touches kable/btleplug — its native lib
// (libbtleplug_ffi.so) is glibc-only and fails to load on musl (e.g. postmarketOS). So no kable
// peripheral is ever created on this target.
actual fun peripheralFromIdentifier(identifier: PebbleBleIdentifier, name: String): Peripheral? = null

actual fun createBlePlatformIdentifier(identifier: PebbleBleIdentifier, name: String): PlatformIdentifier? =
    PlatformIdentifier.BlePlatformIdentifier(null)

actual fun platformGattConnector(
    identifier: PebbleBleIdentifier,
    blePlatformIdentifier: PlatformIdentifier.BlePlatformIdentifier,
    scope: ConnectionCoroutineScope,
): GattConnector = BluezGattConnector(identifier, scope)

// On JVM/Linux, report the actual negotiated ATT MTU rather than echoing back the requested value.
// Returning the requested value causes a "Can't reduce MTU" crash when getMtu() subsequently
// returns the real (smaller) value.
actual suspend fun Peripheral.requestMtuNative(mtu: Int): Int =
    maximumWriteValueLengthForType(WriteType.WithoutResponse) + 3
