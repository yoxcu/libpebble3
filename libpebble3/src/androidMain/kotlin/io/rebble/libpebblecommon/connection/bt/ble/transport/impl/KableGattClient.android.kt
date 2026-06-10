package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PlatformIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope

actual fun peripheralFromIdentifier(identifier: PebbleBleIdentifier, name: String): Peripheral?
 = Peripheral(identifier.macAddress)

actual fun createBlePlatformIdentifier(identifier: PebbleBleIdentifier, name: String): PlatformIdentifier? =
    peripheralFromIdentifier(identifier, name)?.let { PlatformIdentifier.BlePlatformIdentifier(it) }

actual suspend fun Peripheral.requestMtuNative(mtu: Int): Int {
    if (this is AndroidPeripheral) {
        return this.requestMtu(mtu)
    }
    throw IllegalStateException("Not an AndroidPeripheral")
}

actual fun platformGattConnector(
    identifier: PebbleBleIdentifier,
    blePlatformIdentifier: PlatformIdentifier.BlePlatformIdentifier,
    scope: ConnectionCoroutineScope,
): GattConnector = KableGattConnector(identifier, blePlatformIdentifier.peripheral!!, scope)