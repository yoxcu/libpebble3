package io.rebble.libpebblecommon.connection.bt

import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import org.freedesktop.dbus.types.Variant

// Shared BlueZ D-Bus helpers used by both the BLE (BluezBle) and Classic (BluezClassic) transports
// plus the pairing helpers, so they're defined once instead of copy-pasted per transport.

internal const val ORG_BLUEZ = "org.bluez"
internal const val BLUEZ_ADAPTER1 = "org.bluez.Adapter1"
internal const val BLUEZ_DEVICE1 = "org.bluez.Device1"

/** Unwrap a D-Bus [Variant] to its contained value. BlueZ delivers most properties boxed in Variants. */
internal fun unwrapVariant(value: Any?): Any? = if (value is Variant<*>) value.value else value

/** `{"object_path":"/org/bluez/hci0/dev_XX..."}` → `/org/bluez/hci0/dev_XX...` (the PeripheralId format). */
internal fun PebbleBleIdentifier.bluezObjectPath(): String? =
    Regex(""""object_path"\s*:\s*"([^"]+)"""").find(asString)?.groupValues?.get(1)
