package io.rebble.libpebblecommon.connection.bt

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.DBusSignal
import kotlin.time.Duration.Companion.seconds

private val log = Logger.withTag("BluetoothState")

/**
 * Fork (stoandl): Bluetooth on/off from BlueZ. Enabled = some adapter exists and is `Powered`. rfkill needs
 * nothing extra: BlueZ reports a blocked adapter as `Powered=false` (`PowerState=off-blocked`).
 *
 * Upstream returns null on the JVM, so [RealBluetoothStateProvider] fell back to Kable's availability, which
 * stays "available" with the adapter off or blocked: WatchManager then kept a connection attempt running and
 * `Device1.Connect()` failed every few seconds for as long as the adapter was off. With a real state,
 * WatchManager stops the attempts on Disabled and starts them again on Enabled, and GattServerManager re-adds
 * the PPoG service on the next connect.
 *
 * The state is re-read in full (`GetManagedObjects`) on every adapter change: an adapter's `Powered` /
 * `PowerState` change, an adapter added or removed, and bluetoothd (`org.bluez`) starting or stopping.
 */
actual fun nativeBluetoothStateFlow(appContext: AppContext): Flow<BluetoothState>? = callbackFlow {
    val recheck = Channel<Unit>(Channel.CONFLATED)
    var conn: DBusConnection? = null
    launch {
        var last: String? = null
        while (true) {
            val c = conn ?: try {
                DBusConnectionBuilder.forSystemBus().withShared(false).build().also { c ->
                    subscribeAdapterChanges(c) { recheck.trySend(Unit) }
                    conn = c
                }
            } catch (e: Exception) {
                if (last != "no system bus") log.w { "Bluetooth state: no system bus ($e) — treating Bluetooth as off" }
                last = "no system bus"
                send(BluetoothState.Disabled)
                delay(5.seconds)
                continue
            }
            val adapters = readAdapters(c)
            val summary = adapters.joinToString(", ") { it.describe() }.ifEmpty { "no adapter" }
            if (summary != last) {
                log.i { "Bluetooth adapters: $summary" }
                last = summary
            }
            send(if (adapters.any { it.powered }) BluetoothState.Enabled else BluetoothState.Disabled)
            recheck.receive()
        }
    }
    awaitClose { runCatching { conn?.disconnect() } }
}.distinctUntilChanged().flowOn(Dispatchers.IO)

private class AdapterState(val path: String, val powered: Boolean, val powerState: String?) {
    fun describe() = "${path.substringAfterLast('/')} ${powerState ?: if (powered) "on" else "off"}"
}

// No withSender(ORG_BLUEZ): dbus-java also matches a rule locally, against the sender's unique name, so a
// well-known sender never matches. path and arg0 narrow what the bus delivers.
private fun subscribeAdapterChanges(c: DBusConnection, onChange: () -> Unit) {
    c.addGenericSigHandler(
        DBusMatchRuleBuilder.create().withType("signal")
            .withInterface("org.freedesktop.DBus.Properties").withMember("PropertiesChanged")
            .withArg0123(0, BLUEZ_ADAPTER1).build(),
        DBusSigHandler<DBusSignal> { msg -> if (msg.parameters?.firstOrNull() == BLUEZ_ADAPTER1) onChange() },
    )
    for (member in listOf("InterfacesAdded", "InterfacesRemoved")) {
        c.addGenericSigHandler(
            DBusMatchRuleBuilder.create().withType("signal").withPath("/")
                .withInterface("org.freedesktop.DBus.ObjectManager").withMember(member).build(),
            DBusSigHandler<DBusSignal> { onChange() },
        )
    }
    c.addGenericSigHandler(
        DBusMatchRuleBuilder.create().withType("signal").withSender("org.freedesktop.DBus")
            .withInterface("org.freedesktop.DBus").withMember("NameOwnerChanged")
            .withArg0123(0, ORG_BLUEZ).build(),
        DBusSigHandler<DBusSignal> { msg -> if (msg.parameters?.firstOrNull() == ORG_BLUEZ) onChange() },
    )
}

/** Every BlueZ adapter with its power state; empty when bluetoothd isn't running. */
private fun readAdapters(c: DBusConnection): List<AdapterState> = try {
    val objMgr = c.getRemoteObject(ORG_BLUEZ, "/", ObjectManager::class.java)
    @Suppress("UNCHECKED_CAST")
    (objMgr.GetManagedObjects() as Map<DBusPath, Map<String, Map<String, *>>>).mapNotNull { (path, ifaces) ->
        val props = ifaces[BLUEZ_ADAPTER1] ?: return@mapNotNull null
        AdapterState(
            path = path.path,
            powered = unwrapVariant(props["Powered"]) as? Boolean ?: false,
            powerState = unwrapVariant(props["PowerState"]) as? String,
        )
    }.sortedBy { it.path }
} catch (e: Exception) {
    // org.bluez not on the bus (bluetoothd stopped): no adapter. NameOwnerChanged brings us back.
    log.d { "Bluetooth state: cannot read BlueZ adapters: $e" }
    emptyList()
}
