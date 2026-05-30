package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier

actual fun peripheralFromIdentifier(identifier: PebbleBleIdentifier, name: String): Peripheral? {
    val kableId = identifier.kableIdentifier ?: return null
    return Peripheral(kableId) { }
}

// On JVM/Linux, report the actual negotiated ATT MTU rather than echoing back the requested value.
// Returning the requested value causes a "Can't reduce MTU" crash when getMtu() subsequently
// returns the real (smaller) value.
actual suspend fun Peripheral.requestMtuNative(mtu: Int): Int =
    maximumWriteValueLengthForType(WriteType.WithoutResponse) + 3
