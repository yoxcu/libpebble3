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

actual class PebbleBtClassicIdentifier internal constructor() : PebbleIdentifier {
    actual override val asString: String
        get() = throw UnsupportedOperationException("BT Classic not supported on JVM")
}

actual fun String.asPebbleBtClassicIdentifier(): PebbleBtClassicIdentifier {
    throw UnsupportedOperationException("BT Classic not supported on JVM")
}