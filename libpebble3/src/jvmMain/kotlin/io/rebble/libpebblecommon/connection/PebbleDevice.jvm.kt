package io.rebble.libpebblecommon.connection

import com.juul.kable.Identifier

// mac address on android, uuid on ios etc
actual class PebbleBleIdentifier(id: String) : PebbleIdentifier {
    actual override val asString: String = id
    // Retains the underlying Kable Identifier so we can pass it back to Peripheral().
    var kableIdentifier: Identifier? = null

    override fun equals(other: Any?): Boolean = other is PebbleBleIdentifier && asString == other.asString
    override fun hashCode(): Int = asString.hashCode()
    override fun toString(): String = asString
}

actual fun String.asPebbleBleIdentifier(): PebbleBleIdentifier {
    return PebbleBleIdentifier(this)
}

// Bluetooth Classic (BR/EDR) identifier on JVM/Linux: a BlueZ MAC address plus the RFCOMM channel
// of the watch's SPP service (resolved via SDP; defaults to 1). [asString] is just the MAC so it is a
// stable key for WatchManager and maps directly to the BlueZ object path (dev_AA_BB_...).
actual class PebbleBtClassicIdentifier(
    val macAddress: String,
    val rfcommChannel: Int = 1,
) : PebbleIdentifier {
    actual override val asString: String = macAddress

    override fun equals(other: Any?): Boolean =
        other is PebbleBtClassicIdentifier && macAddress == other.macAddress
    override fun hashCode(): Int = macAddress.hashCode()
    override fun toString(): String = "$macAddress (ch$rfcommChannel)"
}

actual fun String.asPebbleBtClassicIdentifier(): PebbleBtClassicIdentifier {
    return PebbleBtClassicIdentifier(this)
}