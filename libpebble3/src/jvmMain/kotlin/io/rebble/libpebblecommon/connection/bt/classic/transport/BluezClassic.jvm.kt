package io.rebble.libpebblecommon.connection.bt.classic.transport

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.PebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.PebbleScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.types.Variant

private val clog = Logger.withTag("BluezClassic")

private const val ORG_BLUEZ = "org.bluez"
internal const val CLASSIC_ADAPTER1 = "org.bluez.Adapter1"
internal const val CLASSIC_DEVICE1 = "org.bluez.Device1"

@DBusInterfaceName("org.bluez.Adapter1")
internal interface ClassicAdapter1 : DBusInterface {
    fun StartDiscovery()
    fun StopDiscovery()
    fun SetDiscoveryFilter(filter: Map<String, Variant<*>>)
}

@DBusInterfaceName("org.bluez.Device1")
internal interface ClassicDevice1 : DBusInterface {
    fun Pair()
    fun Connect()
    fun Disconnect()
}

internal fun unwrapV(value: Any?): Any? = if (value is Variant<*>) value.value else value

private fun macToPathSuffix(mac: String) = "dev_" + mac.trim().uppercase().replace(":", "_")

/** Find the BlueZ object path of a known device by MAC (any adapter), or null if not present. */
internal fun findClassicDevicePath(conn: DBusConnection, mac: String): String? {
    val suffix = macToPathSuffix(mac)
    return try {
        conn.getRemoteObject(ORG_BLUEZ, "/", ObjectManager::class.java)
            .GetManagedObjects().keys
            .map { it.toString() }
            .firstOrNull { it.endsWith("/$suffix") }
    } catch (e: Exception) {
        clog.d { "findClassicDevicePath($mac) failed: ${e.message}" }
        null
    }
}

internal fun findClassicAdapterPath(conn: DBusConnection): String? = try {
    conn.getRemoteObject(ORG_BLUEZ, "/", ObjectManager::class.java)
        .GetManagedObjects().entries
        .firstOrNull { (path, ifaces) ->
            Regex("/org/bluez/hci\\d+$").matches(path.toString()) && CLASSIC_ADAPTER1 in ifaces
        }?.key?.toString()
} catch (e: Exception) {
    clog.w { "findClassicAdapterPath failed: ${e.message}" }
    null
}

/**
 * BR/EDR (Bluetooth Classic) scanner: discovers classic-era Pebbles via BlueZ inquiry. Mirrors
 * BluezBleScanner but with Transport=bredr, emitting a [PebbleBtClassicIdentifier]. A discovered
 * device object is what lets the connector's Device1.Pair() do a BR/EDR bond.
 */
class BluezClassicScanner : ClassicScanner {
    private companion object {
        // Process-lifetime connection so discovered (temporary) device objects survive between cycles.
        private val sharedConn: DBusConnection by lazy {
            DBusConnectionBuilder.forSystemBus().withShared(false).build()
        }
    }

    override fun scan(): Flow<PebbleScanResult> = callbackFlow {
        val conn = sharedConn
        val adapterPath = findClassicAdapterPath(conn)
        if (adapterPath == null) {
            clog.w { "no adapter found; cannot BR/EDR scan" }
            close(); return@callbackFlow
        }
        val adapter = conn.getRemoteObject(ORG_BLUEZ, adapterPath, ClassicAdapter1::class.java)
        val objMgr = conn.getRemoteObject(ORG_BLUEZ, "/", ObjectManager::class.java)

        try {
            adapter.SetDiscoveryFilter(mapOf("Transport" to Variant("bredr")))
        } catch (e: Exception) {
            clog.w { "SetDiscoveryFilter(bredr) failed: ${e.message}" }
        }
        try {
            adapter.StartDiscovery()
            clog.d { "BR/EDR discovery started on $adapterPath" }
        } catch (e: Exception) {
            clog.w { "StartDiscovery (bredr) failed: ${e.message}" }
        }

        val poller = launch(Dispatchers.IO) {
            var lastMatched = -1
            while (isActive) {
                try {
                    var matched = 0
                    objMgr.GetManagedObjects().forEach { (p, ifaces) ->
                        val dp = ifaces[CLASSIC_DEVICE1] ?: return@forEach
                        val name = (unwrapV(dp["Name"]) as? String)
                            ?: (unwrapV(dp["Alias"]) as? String) ?: ""
                        val addr = (unwrapV(dp["Address"]) as? String) ?: return@forEach
                        // Classic-era Pebble: a BR/EDR device named "Pebble …" (e.g. "Pebble Time 1E81").
                        // Exclude the "Pebble Time LE …" BLE bridge name defensively (it shouldn't appear
                        // under a bredr filter anyway).
                        if (!name.startsWith("Pebble", ignoreCase = true)) return@forEach
                        if (name.contains(" LE ", ignoreCase = true)) return@forEach
                        val rssi = (unwrapV(dp["RSSI"]) as? Number)?.toInt() ?: 0
                        matched++
                        trySend(
                            PebbleScanResult(
                                identifier = PebbleBtClassicIdentifier(addr),
                                name = name,
                                rssi = rssi,
                                leScanRecord = null,
                            )
                        )
                    }
                    if (matched != lastMatched) {
                        clog.i { "BluezClassicScanner: pebble match count $lastMatched -> $matched" }
                        lastMatched = matched
                    }
                } catch (e: Exception) {
                    clog.w { "BR/EDR poll failed: ${e.message}" }
                }
                delay(2000)
            }
        }

        awaitClose {
            poller.cancel()
            try { adapter.StopDiscovery() } catch (_: Exception) {}
        }
    }
}
