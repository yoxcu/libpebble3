package io.rebble.libpebblecommon.connection.bt

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectivityStatus
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_BONDED
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.BOND_NONE
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

private val log = Logger.withTag("PairingJvm")

// Extracts /org/bluez/hci0/dev_XX_XX... from {"object_path":"/org/bluez/..."}
private fun PebbleBleIdentifier.bluezObjectPath(): String? =
    Regex(""""object_path"\s*:\s*"([^"]+)"""").find(asString)?.groupValues?.get(1)

actual fun isBonded(identifier: PebbleBleIdentifier): Boolean {
    val objectPath = identifier.bluezObjectPath() ?: return false
    return try {
        val conn = DBusConnectionBuilder.forSystemBus().withShared(false).build()
        try {
            val props = conn.getRemoteObject("org.bluez", objectPath, Properties::class.java)
            val result = props.Get<Any>("org.bluez.Device1", "Paired")
            when (result) {
                is Boolean -> result
                is Variant<*> -> result.value as? Boolean ?: false
                else -> false
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        // UnknownObject is expected when BlueZ has dropped a transient (discovered-but-not-yet-bonded)
        // device; treat as not-bonded without the noisy stacktrace.
        log.d { "isBonded check failed for $identifier: ${e.message}" }
        false
    }
}

@DBusInterfaceName("org.bluez.Device1")
@Suppress("FunctionName")
private interface BluezDevice1 : DBusInterface {
    fun Pair()
}

actual fun createBond(identifier: PebbleBleIdentifier): Boolean {
    val objectPath = identifier.bluezObjectPath() ?: return false
    // Skip Pair() if already bonded: calling Pair() on a bonded device triggers BlueZ SMP
    // re-authentication that blocks BLE for ~12 seconds before returning AuthenticationCanceled,
    // preventing PPoG RESET_REQUEST from arriving within the timeout.
    if (isBonded(identifier)) {
        log.i { "createBond: already bonded, skipping Pair() for $identifier" }
        return true
    }
    Thread(null, {
        try {
            val conn = DBusConnectionBuilder.forSystemBus().withShared(false).build()
            try {
                val device = conn.getRemoteObject("org.bluez", objectPath, BluezDevice1::class.java)
                log.i { "Calling Pair() on $objectPath" }
                device.Pair()
                log.i { "Pair() completed for $objectPath" }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // AuthenticationCanceled is expected: after BLE bonding completes, the watch
            // disconnects to re-establish the connection encrypted. BlueZ returns
            // AuthenticationCanceled for the pending Pair() call when the connection drops.
            // Bond completion is detected via connectivity characteristic in getBluetoothDevicePairEvents().
            log.i { "createBond Pair() ended for $identifier: ${e.message}" }
        }
    }, "bluez-pair").also { it.isDaemon = true }.start()
    return true
}

actual fun getBluetoothDevicePairEvents(
    context: AppContext,
    identifier: PebbleBleIdentifier,
    connectivity: Flow<ConnectivityStatus>,
): Flow<BluetoothDevicePairEvent> {
    val objectPath = identifier.bluezObjectPath() ?: return emptyFlow()
    return callbackFlow {
        val conn = DBusConnectionBuilder.forSystemBus().withShared(false).build()
        // Subscribe to Paired property changes before checking current state to avoid races.
        val matchRule = DBusMatchRuleBuilder.create()
            .withType("signal")
            .withInterface("org.freedesktop.DBus.Properties")
            .withMember("PropertiesChanged")
            .withPath(objectPath)
            .build()
        conn.addGenericSigHandler(matchRule) { msg: DBusSignal ->
            try {
                val params = msg.getParameters()
                if (params != null && params.size >= 2 && params[0] as? String == "org.bluez.Device1") {
                    @Suppress("UNCHECKED_CAST")
                    val changed = params[1] as? Map<*, *>
                    val variant = changed?.get("Paired")
                    val rawPaired = if (variant is Variant<*>) variant.value else variant
                    val paired = rawPaired as? Boolean
                    if (paired != null) {
                        log.d { "Paired property changed: $paired" }
                        trySend(BluetoothDevicePairEvent(identifier, if (paired) BOND_BONDED else BOND_NONE, null))
                    }
                }
            } catch (e: Exception) {
                log.w(e) { "PropertiesChanged parse error" }
            }
        }
        // Also detect bonding via the watch's connectivity characteristic. This handles the
        // common case where BlueZ creates a new device entry for the resolved public address
        // after bonding, so the Paired D-Bus signal never appears on the original object path.
        val connectivityJob = launch {
            connectivity.collect { status ->
                if (status.paired) {
                    log.d { "Connectivity reports paired: $identifier" }
                    trySend(BluetoothDevicePairEvent(identifier, BOND_BONDED, null))
                }
            }
        }
        // Also emit the current state in case Pair() completed before we started collecting.
        if (isBonded(identifier)) {
            log.d { "Already bonded: $identifier" }
            trySend(BluetoothDevicePairEvent(identifier, BOND_BONDED, null))
        }
        awaitClose {
            connectivityJob.cancel()
            conn.disconnect()
        }
    }
}

actual fun isBondedClassic(identifier: PebbleBtClassicIdentifier): Boolean = false
actual fun createBondClassic(identifier: PebbleBtClassicIdentifier): Boolean = false
actual fun getBluetoothClassicDevicePairEvents(
    context: AppContext,
    identifier: PebbleBtClassicIdentifier,
): Flow<BluetoothClassicDevicePairEvent> = emptyFlow()
