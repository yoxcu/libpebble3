package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import co.touchlab.kermit.Logger
import com.juul.kable.ManufacturerData
import io.rebble.libpebblecommon.connection.BleScanResult
import io.rebble.libpebblecommon.connection.ConnectionFailureReason
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.BLUEZ_ADAPTER1
import io.rebble.libpebblecommon.connection.bt.BLUEZ_DEVICE1
import io.rebble.libpebblecommon.connection.bt.ORG_BLUEZ
import io.rebble.libpebblecommon.connection.bt.bluezObjectPath
import io.rebble.libpebblecommon.connection.bt.unwrapVariant
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattCharacteristic
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnectionResult
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattService
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattWriteType
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val log = Logger.withTag("BluezBle")

// Pebble (0x0154) and Core Devices (0x0EEA) BLE company identifiers. A watch may advertise more
// than one manufacturer-data entry; prefer one of these so the downstream vendor filter keeps it.
private val PEBBLE_VENDOR_IDS = setOf(0x0154, 0x0EEA)

private const val GATT_SERVICE1 = "org.bluez.GattService1"
private const val GATT_CHARACTERISTIC1 = "org.bluez.GattCharacteristic1"
private const val DBUS_PROPERTIES = "org.freedesktop.DBus.Properties"

// ATT default MTU; used only as a safe floor when BlueZ doesn't expose the negotiated MTU yet.
private const val DEFAULT_ATT_MTU = 23

@DBusInterfaceName("org.bluez.Adapter1")
private interface BluezAdapter1 : DBusInterface {
    fun StartDiscovery()
    fun StopDiscovery()
    fun SetDiscoveryFilter(filter: Map<String, Variant<*>>)
}

@DBusInterfaceName("org.bluez.Device1")
private interface BluezDevice1 : DBusInterface {
    fun Connect()
    fun Disconnect()
}

@DBusInterfaceName("org.bluez.GattCharacteristic1")
private interface BluezGattCharacteristic1Client : DBusInterface {
    fun ReadValue(options: Map<String, Variant<*>>): ByteArray
    fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>)
    fun StartNotify()
    fun StopNotify()
}

/**
 * Coerce a D-Bus `ay` value to a ByteArray. When a byte array is nested inside a Variant (e.g.
 * ManufacturerData `a{qv}` or a characteristic's Value), dbus-java may deliver it as boxed `Byte[]`
 * or a `List`, not a primitive `byte[]`, so a plain `as? ByteArray` cast silently fails.
 */
private fun asByteArray(value: Any?): ByteArray? = when (value) {
    is ByteArray -> value
    is Array<*> -> value.mapNotNull { (it as? Number)?.toByte() }.toByteArray()
    is List<*> -> value.mapNotNull { (it as? Number)?.toByte() }.toByteArray()
    else -> null
}

/** Build the identifier asString in the btleplug PeripheralId format so isBonded()/pairing keep working. */
private fun objectPathToAsString(path: String): String = """{"object_path":"$path"}"""

private fun isHciAdapter(path: String) = Regex("/org/bluez/hci\\d+$").matches(path)

private fun findAdapterPath(conn: DBusConnection): String? = try {
    val objMgr = conn.getRemoteObject(ORG_BLUEZ, "/", ObjectManager::class.java)
    objMgr.GetManagedObjects().entries
        .firstOrNull { (path, ifaces) -> isHciAdapter(path.toString()) && BLUEZ_ADAPTER1 in ifaces }
        ?.key?.toString()
} catch (e: Exception) {
    log.w(e) { "findAdapterPath failed" }
    null
}

/**
 * Pure-BlueZ BLE scanner (replaces the kable/btleplug scanner on the JVM/Linux target). Drives
 * org.bluez.Adapter1 discovery directly over D-Bus, then polls GetManagedObjects for each device's
 * ManufacturerData/RSSI — robust against signal-delivery timing, and works on musl systems where the
 * btleplug native lib is unavailable.
 */
class BluezBleScanner : BleScanner {
    private companion object {
        // One process-lifetime D-Bus connection for discovery, reused across scan cycles and never
        // disconnected. When a discovery client disconnects, BlueZ removes the *temporary* device
        // objects it found — which would make a just-discovered watch vanish before
        // BluezGattConnector can Connect() to it. Keeping the connection open lets the device survive
        // the StopDiscovery between cycles and the scan→connect handoff (BlueZ's temporary-device
        // timeout is ~30s, ample to establish the connection, after which the device is permanent).
        private val sharedConn: DBusConnection by lazy {
            DBusConnectionBuilder.forSystemBus().withShared(false).build()
        }
    }

    override fun scan(): Flow<BleScanResult> = callbackFlow {
        val producer = this
        val conn = sharedConn
        val adapterPath = findAdapterPath(conn)
        if (adapterPath == null) {
            log.w { "BluezBleScanner: no adapter found; cannot scan" }
            close()
            return@callbackFlow
        }
        val adapter = conn.getRemoteObject(ORG_BLUEZ, adapterPath, BluezAdapter1::class.java)
        val objMgr = conn.getRemoteObject(ORG_BLUEZ, "/", ObjectManager::class.java)

        fun emitDevice(devicePath: String, deviceProps: Map<String, Variant<*>>): Boolean {
            return try {
                val mdRaw = unwrapVariant(deviceProps["ManufacturerData"]) as? Map<*, *> ?: return false
                var match: Pair<Int, ByteArray>? = null
                for ((k, v) in mdRaw) {
                    val code = (k as? Number)?.toInt() ?: continue
                    if (code !in PEBBLE_VENDOR_IDS) continue
                    val bytes = asByteArray(unwrapVariant(v)) ?: continue
                    match = code to bytes
                    break
                }
                val (code, bytes) = match ?: return false
                val name = (unwrapVariant(deviceProps["Name"]) as? String)
                    ?: (unwrapVariant(deviceProps["Alias"]) as? String) ?: ""
                val rssi = (unwrapVariant(deviceProps["RSSI"]) as? Number)?.toInt() ?: 0
                // asString carries the BlueZ object path (used by isBonded/pairing/the connector).
                // No kableIdentifier: the JVM/BlueZ path never builds a kable peripheral (btleplug's
                // native lib can't load on musl).
                val id = PebbleBleIdentifier(objectPathToAsString(devicePath))
                producer.trySend(BleScanResult(id, name, rssi, ManufacturerData(code, bytes)))
                true
            } catch (e: Exception) {
                log.w(e) { "emitDevice failed for $devicePath" }
                false
            }
        }

        try {
            adapter.SetDiscoveryFilter(mapOf("Transport" to Variant("le")))
        } catch (e: Exception) {
            log.w { "BluezBleScanner: SetDiscoveryFilter failed: ${e.message}" }
        }
        try {
            adapter.StartDiscovery()
            log.d { "BluezBleScanner: discovery started on $adapterPath" }
        } catch (e: Exception) {
            log.w { "BluezBleScanner: StartDiscovery failed: ${e.message}" }
        }

        val poller = launch(Dispatchers.IO) {
            var loggedSummary = false
            var lastMatched = -1
            while (isActive) {
                try {
                    val managed = objMgr.GetManagedObjects()
                    var total = 0
                    var matched = 0
                    val withMfd = StringBuilder()
                    managed.forEach { (p, ifaces) ->
                        val dp = ifaces[BLUEZ_DEVICE1] ?: return@forEach
                        total++
                        if (!loggedSummary) {
                            val md = unwrapVariant(dp["ManufacturerData"]) as? Map<*, *>
                            if (md != null) {
                                val codes = md.keys.mapNotNull { (it as? Number)?.toInt() }
                                    .joinToString(",") { "0x%04x".format(it) }
                                val nm = (unwrapVariant(dp["Name"]) as? String)
                                    ?: (unwrapVariant(dp["Alias"]) as? String) ?: "?"
                                withMfd.append(" [$nm mfd=$codes]")
                            }
                        }
                        if (emitDevice(p.toString(), dp)) matched++
                    }
                    if (!loggedSummary) {
                        log.d { "BluezBleScanner: $total devices, $matched pebble match; mfd-devices:$withMfd" }
                        loggedSummary = true
                    } else if (matched != lastMatched) {
                        // Surfaces a pebble watch appearing/disappearing in discovery.
                        log.i { "BluezBleScanner: pebble match count $lastMatched -> $matched" }
                    }
                    lastMatched = matched
                } catch (e: Exception) {
                    log.w(e) { "BluezBleScanner: poll failed" }
                }
                delay(2000)
            }
        }

        awaitClose {
            poller.cancel()
            // Log (don't swallow) a StopDiscovery failure: because sharedConn is process-lifetime and
            // never closed, a failed stop leaves the discovery session HELD on it — Adapter1.Discovering
            // stays true while we believe we stopped scanning, which reads as an external scanner
            // blocking reconnection. Surfacing it turns that silent leak into a visible log line.
            try { adapter.StopDiscovery() } catch (e: Exception) {
                log.w { "BluezBleScanner: StopDiscovery failed; discovery may remain active: ${e.message}" }
            }
            // Intentionally do NOT disconnect the shared connection — see companion note. Keeping it
            // open preserves the temporary device objects so the connector can reach them.
        }
    }
}

/**
 * Pure-BlueZ outbound GATT connector (replaces kable's KableGattConnector on JVM/Linux). Establishes
 * the LE connection via org.bluez.Device1.Connect() and waits for ServicesResolved before handing
 * back a [BluezConnectedGattClient].
 */
class BluezGattConnector(
    private val identifier: PebbleBleIdentifier,
    private val scope: ConnectionCoroutineScope,
) : GattConnector {
    private val logger = Logger.withTag("BluezGattConnector/${identifier.asString}")
    private val devicePath = identifier.bluezObjectPath()
    private val _disconnected = CompletableDeferred<ConnectionFailureReason>()
    override val disconnected: Deferred<ConnectionFailureReason> = _disconnected

    private var conn: DBusConnection? = null
    private var lifecycleHandle: AutoCloseable? = null
    private var reasonHandle: AutoCloseable? = null
    // Last org.bluez.Device1.Disconnected reason (e.g. "org.bluez.Reason.Timeout"), captured just
    // before the Connected=false edge so the "link dropped" log can name why. Best-effort: empty on
    // BlueZ < 5.83 (no such signal) or if the property edge happens to be delivered first.
    @Volatile private var lastDropReason: String? = null
    private var attempted = false

    override suspend fun connect(): GattConnectionResult {
        val path = devicePath ?: run {
            logger.e("connect(): no bluez object path in identifier")
            _disconnected.complete(ConnectionFailureReason.FailedToConnect)
            return GattConnectionResult.Failure(ConnectionFailureReason.FailedToConnect)
        }
        logger.i { "connect() starting for $path" }
        val c = DBusConnectionBuilder.forSystemBus().withShared(false).build()
        conn = c
        val device = c.getRemoteObject(ORG_BLUEZ, path, BluezDevice1::class.java)
        val props = c.getRemoteObject(ORG_BLUEZ, path, Properties::class.java)

        val resolved = CompletableDeferred<Boolean>()
        // Once the link has come up, a later Connected=false is a real disconnect we must surface.
        // While still arming, Connected=false is just a failed background attempt and is ignored —
        // BlueZ keeps the accept-list intent and ServicesResolved fires when the watch returns.
        val linkUp = AtomicBoolean(false)
        val rule = DBusMatchRuleBuilder.create()
            .withType("signal").withInterface(DBUS_PROPERTIES)
            .withMember("PropertiesChanged").withPath(path).build()
        lifecycleHandle = c.addGenericSigHandler(rule) { msg: DBusSignal ->
            try {
                val params = msg.getParameters() ?: return@addGenericSigHandler
                if (params.size < 2 || params[0] != BLUEZ_DEVICE1) return@addGenericSigHandler
                val changed = params[1] as? Map<*, *> ?: return@addGenericSigHandler
                (unwrapVariant(changed["ServicesResolved"]) as? Boolean)?.let {
                    if (it) { linkUp.set(true); resolved.complete(true) }
                }
                (unwrapVariant(changed["Connected"]) as? Boolean)?.let {
                    if (!it && linkUp.get()) {
                        logger.i { "device reported Connected=false (link dropped${reasonSuffix(lastDropReason)})" }
                        if (!_disconnected.isCompleted) _disconnected.complete(ConnectionFailureReason.FailedToConnect)
                    }
                }
            } catch (e: Exception) {
                logger.w(e) { "lifecycle handler error" }
            }
        }

        // Capture the BlueZ disconnect reason so the "link dropped" log can tell out-of-range
        // (Reason.Timeout) from a broken bond (Reason.Authentication) without a btmon snoop. The
        // org.bluez.Device1.Disconnected(reason, message) signal lands in BlueZ >= 5.83, normally
        // just before the Connected=false property edge; on older BlueZ it never fires and the log
        // simply omits the reason. (The churn detector in PebbleIntegration keys broken-bond
        // detection off this same signal — this is purely for diagnosability.)
        val reasonRule = DBusMatchRuleBuilder.create()
            .withType("signal").withInterface(BLUEZ_DEVICE1)
            .withMember("Disconnected").withPath(path).build()
        reasonHandle = c.addGenericSigHandler(reasonRule) { msg: DBusSignal ->
            try {
                lastDropReason = msg.getParameters()?.getOrNull(0) as? String
            } catch (e: Exception) {
                logger.v(e) { "reason handler error" }
            }
        }

        // Trust the bonded device so bluetoothd keeps it in the kernel background-connect (accept-list)
        // set and reconnects it the instant it advertises — no app-side polling. Harmless if unbonded.
        try { props.Set(BLUEZ_DEVICE1, "Trusted", Variant(true, "b")) } catch (e: Exception) {
            logger.d { "could not set Trusted: ${e.message}" }
        }

        attempted = true
        if (readBool(props, "ServicesResolved")) { linkUp.set(true); resolved.complete(true) }

        // Tear down this connector (both signal handlers AND the DBusConnection) exactly once when the
        // connection ends. On a successful connect close() is never reached via the finally below
        // (succeeded=true), so without this the handlers + connection leak for the life of every
        // session — and on a one-sided/stale bond, where BlueZ's Trusted auto-connect keeps
        // re-establishing a dead link, a fresh handler leaks per cycle and they ALL fire on the next
        // drop (the duplicated "link dropped" logs).
        //
        // This must NOT run in `scope`: that's the per-connection ConnectionCoroutineScope, which the
        // reconnect machinery cancels in cleanup() the instant the connection ends — racing the await
        // and frequently skipping the close (the actual cause of the leak the old comment described).
        // invokeOnCompletion fires regardless of any scope's cancellation; it just hands the blocking
        // close() to an independent IO coroutine (closing a DBusConnection from its own signal-receiver
        // thread, where _disconnected completes, would deadlock).
        _disconnected.invokeOnCompletion { cleanupScope.launch { close() } }

        // Standing connection intent. Keep a Device1.Connect() pending and re-issue it slowly; NEVER
        // call Disconnect() on failure — that cancels BlueZ's kernel connect intent and is what turned
        // reconnection into a losing race against the watch's advertising window. For a bonded watch
        // this suspends at near-zero cost until the watch advertises and BlueZ links up. A genuinely
        // stale bond surfaces as Connect() throwing "doesn't exist" → terminal FailedToConnect, which
        // the stale-bond reaper then clears. Cancellation (BT off / requestDisconnection / forget)
        // unwinds via the scope and is cleaned up in the finally.
        var succeeded = false
        // Arming-phase reconcile bookkeeping (consumed by the block after attempt.cancel() below):
        // consecutive cycles seen stuck (Connected && !ServicesResolved) / unreadable (null state).
        var unresolvedCycles = 0
        var unreadableCycles = 0
        try {
            while (scope.isActive) {
                val attempt = scope.launch(Dispatchers.IO) {
                    try {
                        device.Connect()
                        logger.d { "Connect() returned" }
                    } catch (e: Exception) {
                        val m = e.message ?: ""
                        if (m.contains("doesn't exist")) {
                            if (!_disconnected.isCompleted) _disconnected.complete(ConnectionFailureReason.FailedToConnect)
                            if (!resolved.isCompleted) resolved.complete(false)
                        } else {
                            // "No reply within specified time" (client gave up while BlueZ keeps
                            // trying), "In Progress", "Already Connected", out-of-range — all tolerable;
                            // do NOT Disconnect, or we'd cancel the kernel connect intent.
                            logger.d { "Connect() pending/failed, tolerating: $m" }
                        }
                    }
                }
                // Unblock as soon as the link resolves OR the Connect() attempt ends. An away watch
                // keeps Connect() pending, so we wait on `resolved` (capped by REARM_INTERVAL as
                // insurance). But a Connect() that returns or fails WITHOUT establishing — e.g. a 0x3e
                // "connection failed to be established" flap from reconnecting too fast after a restart
                // — ends `attempt`; we then re-arm after a short backoff instead of sitting idle for a
                // whole minute with no connection attempt pending in BlueZ (which is what made a
                // service restart take ~60s to reconnect).
                withTimeoutOrNull(REARM_INTERVAL) {
                    select<Unit> {
                        resolved.onAwait { }
                        attempt.onJoin { }
                    }
                }
                attempt.cancel()

                // Reconcile against BlueZ's OWN state each arming cycle. The signal handler above is
                // edge-triggered, so if the ServicesResolved false->true edge is missed (raced/never
                // delivered to this long-lived per-attempt connection, Case A) or never emitted (ACL up
                // but GATT resolution stuck, Case B), `resolved` never completes and the loop would
                // re-arm Connect() — a no-op "Already Connected" — forever, wedged with the link up.
                // This is the arming-phase analog of reconcileConnectedState(): trust BlueZ's level, not
                // just our cached edge.
                if (!resolved.isCompleted) {
                    when (val connected = readBoolOrNull(props, "Connected")) {
                        null -> {
                            // Our connection can't read the device — likely a dead/deaf socket. After a
                            // couple of cycles (tolerating a transient hiccup) fail out so the reconnect
                            // machinery rebuilds the connector on a FRESH DBusConnection, whose entry read
                            // (readBool ServicesResolved above the loop) re-checks live state cleanly.
                            // close() only drops our D-Bus client, NOT the BLE link, so the kernel
                            // accept-list connect intent survives.
                            if (++unreadableCycles >= UNREADABLE_CYCLES) {
                                logger.w { "arming: device state unreadable on this connection — rebuilding connector" }
                                if (!_disconnected.isCompleted) _disconnected.complete(ConnectionFailureReason.FailedToConnect)
                                return GattConnectionResult.Failure(ConnectionFailureReason.FailedToConnect)
                            }
                        }
                        else -> {
                            unreadableCycles = 0
                            if (readBool(props, "ServicesResolved")) {
                                // A ServicesResolved edge we missed; recover from the level. (Case A.)
                                linkUp.set(true)
                                resolved.complete(true)
                            } else if (connected) {
                                // ACL up but GATT unresolved — a stuck half-link that re-Connect() can't
                                // clear (BlueZ answers "Already Connected"). After STUCK_RESOLVE_CYCLES of
                                // confirmed stuck-state, force ONE Disconnect so the next Connect() re-
                                // establishes and re-resolves. Safe w.r.t. the "never Disconnect while
                                // arming" rule, which guards only the Connected==false out-of-range path:
                                // here the watch is present, so this can't lose an advertising window.
                                // (Case B — the observed 9h wedge: Connected=true, ServicesResolved=false.)
                                if (++unresolvedCycles >= STUCK_RESOLVE_CYCLES) {
                                    logger.i { "connected but services unresolved for $unresolvedCycles cycles — forcing reconnect to re-resolve GATT" }
                                    try { device.Disconnect() } catch (e: Exception) { logger.d { "stuck-resolve Disconnect failed: ${e.message}" } }
                                    unresolvedCycles = 0
                                }
                            } else {
                                // Genuinely out of range (Connected=false) — normal arming; keep the
                                // standing intent, never Disconnect.
                                unresolvedCycles = 0
                            }
                        }
                    }
                }

                if (resolved.isCompleted) {
                    if (resolved.await()) {
                        logger.i { "connected and services resolved" }
                        succeeded = true
                        scope.launch(Dispatchers.IO) { reconcileConnectedState(props, linkUp) }
                        return GattConnectionResult.Success(BluezConnectedGattClient(identifier, c, path))
                    }
                    val reason = if (_disconnected.isCompleted) ConnectionFailureReason.FailedToConnect
                    else ConnectionFailureReason.ConnectTimeout
                    if (!_disconnected.isCompleted) _disconnected.complete(reason)
                    return GattConnectionResult.Failure(reason)
                }
                // No link yet: the attempt ended (or the cap elapsed). A brief settle keeps us from
                // reconnecting faster than the watch can cleanly re-advertise, then we re-arm.
                logger.d { "connect attempt ended without a link; re-arming in $RETRY_BACKOFF" }
                delay(RETRY_BACKOFF)
            }
            // Scope cancelled (Bluetooth off / requestDisconnection / forget).
            if (!_disconnected.isCompleted) _disconnected.complete(ConnectionFailureReason.FailedToConnect)
            return GattConnectionResult.Failure(ConnectionFailureReason.FailedToConnect)
        } finally {
            if (!succeeded) close()
        }
    }

    override suspend fun disconnect() {
        logger.d { "disconnect()" }
        val c = conn
        val path = devicePath
        if (c != null && path != null) {
            try { c.getRemoteObject(ORG_BLUEZ, path, BluezDevice1::class.java).Disconnect() } catch (_: Exception) {}
        }
        if (!attempted && !_disconnected.isCompleted) {
            _disconnected.complete(ConnectionFailureReason.NotAnError_NeverAttmpedConnection)
        }
    }

    override fun close() {
        try { lifecycleHandle?.close() } catch (_: Exception) {}
        try { reasonHandle?.close() } catch (_: Exception) {}
        try { conn?.disconnect() } catch (_: Exception) {}
        conn = null
    }

    private fun readBool(props: Properties, name: String): Boolean = try {
        (unwrapVariant(props.Get<Any>(BLUEZ_DEVICE1, name)) as? Boolean) ?: false
    } catch (_: Exception) { false }

    // Like readBool but returns null when the property can't be read at all (D-Bus call throws/times
    // out) — i.e. our connection to BlueZ is dead — vs. a genuine false. Used by the arming reconcile
    // to tell "device is out of range" (Connected reads false) from "our connection is deaf" (null).
    private fun readBoolOrNull(props: Properties, name: String): Boolean? = try {
        unwrapVariant(props.Get<Any>(BLUEZ_DEVICE1, name)) as? Boolean
    } catch (_: Exception) { null }

    // Safety net for the signal-driven path above: periodically re-read BlueZ's own Connected
    // property directly (not the cached PropertiesChanged state) and reconcile if it disagrees
    // with what we believe. Only ever fires _disconnected when BlueZ ITSELF reports the device
    // gone, so it can't false-positive on a healthy connection that's simply been up for a long
    // time — it's a backstop against a missed/raced PropertiesChanged signal (e.g. the
    // ServicesResolved fast-path above completing on a stale read without a matching Connected
    // edge ever arriving), not a liveness timeout.
    private suspend fun reconcileConnectedState(props: Properties, linkUp: AtomicBoolean) {
        while (scope.isActive && linkUp.get() && !_disconnected.isCompleted) {
            delay(RECONCILE_INTERVAL)
            if (!linkUp.get() || _disconnected.isCompleted) return
            if (!readBool(props, "Connected")) {
                logger.w { "reconcile: BlueZ reports Connected=false with no disconnection signalled — forcing disconnect" }
                if (!_disconnected.isCompleted) _disconnected.complete(ConnectionFailureReason.FailedToConnect)
                return
            }
        }
    }

    // Render a BlueZ disconnect reason as a short, log-friendly suffix. Strips the "org.bluez.Reason."
    // prefix and adds a plain-English hint for the two that matter to the reconnect saga.
    private fun reasonSuffix(reason: String?): String {
        if (reason.isNullOrBlank()) return ""
        val short = reason.removePrefix("org.bluez.Reason.")
        val hint = when (short) {
            "Timeout" -> " — out of range"
            "Authentication" -> " — auth failure / broken bond"
            "ConnectionAttemptFailed" -> " — RF connection failed (0x3e)"
            "LocalHostTerminated" -> " — local host terminated"
            "RemoteUserTerminated" -> " — watch terminated"
            else -> ""
        }
        return ", reason: $short$hint"
    }

    companion object {
        // Independent of any per-connection scope so end-of-connection teardown (close()) still runs
        // after the reconnect machinery cancels the ConnectionCoroutineScope. Only ever runs a brief
        // close(); SupervisorJob so one failing teardown can't take down the next.
        private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // How often to re-issue Device1.Connect() while arming. Long, because a single Connect()
        // already leaves a standing kernel intent (BlueZ keeps trying after the D-Bus call's client
        // reply times out); this is only insurance in case BlueZ drops it. Must exceed the ~20s D-Bus
        // reply timeout so attempts don't pile up.
        //
        // KNOWN REDUNDANT (kept as zero-cost insurance): a 2026-06-12 btmon snoop of an ~11 min
        // airplane-mode window proved BlueZ reconnects on its OWN — one Device1.Connect() arms an
        // accept-list passive scan that BlueZ re-runs on a steady 45s duty cycle (MGMT Connect Failed
        // 0x0e each time the watch is absent) and completes the instant the watch advertises again
        // (3s, in that capture). Our 60s re-arm produced ZERO extra LE Create Connection on the wire
        // the entire window — BlueZ swallows the re-issues as "in progress". So this cap can be
        // DROPPED with no behaviour change for the out-of-range case: just block on
        // `select { resolved-onAwait, attempt-onJoin }` with no timeout and rely on RETRY_BACKOFF (which is
        // the load-bearing part — it handles a Connect() that ENDS without a link, e.g. a 0x3e flap,
        // a case the airplane snoop doesn't exercise). Left in only to guard the hypothetical where
        // BlueZ silently drops the intent (never observed: held 11 min on the snoop, 5h40m overnight).
        private val REARM_INTERVAL = 60.seconds
        // Settle after a Connect() that ended without establishing a link (e.g. a 0x3e flap) before
        // re-arming — short enough to recover a restart in seconds, long enough to let the watch
        // re-advertise cleanly rather than flapping again on an even faster reconnect.
        private val RETRY_BACKOFF = 5.seconds
        // How often reconcileConnectedState() re-checks BlueZ's Connected property directly.
        // Just a backstop against a missed signal, so this can be relaxed; short enough that an
        // overnight wedge self-heals within one cycle instead of hanging for hours.
        private val RECONCILE_INTERVAL = 5.minutes
        // Consecutive arming cycles BlueZ may report Connected=true && ServicesResolved=false before we
        // force a Disconnect() to re-resolve GATT. Normal resolution completes in <5s; each arming cycle
        // is paced by RETRY_BACKOFF (~5s) since a Connect() on an already-connected device returns fast,
        // so ~4 cycles ≈ 20s of confirmed stuck-state — long enough not to interrupt a resolve in
        // progress, short enough to unstick a wedged half-link quickly. (Case B: ACL up, GATT stuck.)
        private val STUCK_RESOLVE_CYCLES = 4
        // Consecutive cycles our own DBusConnection may fail to read the device's state before we fail
        // out so the reconnect machinery rebuilds the connector on a FRESH connection. Guards the case
        // where a long-lived per-attempt connection goes deaf (missed edge on a dead socket, Case A)
        // while tolerating a transient Get() hiccup.
        private val UNREADABLE_CYCLES = 2
    }
}

private class BluezConnectedGattClient(
    private val identifier: PebbleBleIdentifier,
    private val conn: DBusConnection,
    private val devicePath: String,
) : ConnectedGattClient {
    private val logger = Logger.withTag("BluezConnectedGattClient/${identifier.asString}")

    // (serviceUuid, characteristicUuid) -> characteristic D-Bus object path. Rebuilt by discover();
    // volatile because GATT operations run on different coroutine threads.
    @Volatile private var charPaths: Map<Pair<Uuid, Uuid>, String> = emptyMap()
    @Volatile private var _services: List<GattService>? = null
    @Volatile private var lastDiscoverMs = 0L
    private val discoverLock = Any()

    override val services: List<GattService>? get() = _services

    init {
        discover()
    }

    private fun discover() {
        synchronized(discoverLock) {
            val newCharPaths = HashMap<Pair<Uuid, Uuid>, String>()
            val out = ArrayList<GattService>()
            try {
                val objMgr = conn.getRemoteObject(ORG_BLUEZ, "/", ObjectManager::class.java)
                val managed = objMgr.GetManagedObjects()
                val prefix = "$devicePath/"
                val serviceUuidByPath = HashMap<String, Uuid>()
                managed.forEach { (p, ifaces) ->
                    val ps = p.toString()
                    if (!ps.startsWith(prefix)) return@forEach
                    val svc = ifaces[GATT_SERVICE1] ?: return@forEach
                    val uuid = (unwrapVariant(svc["UUID"]) as? String)?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    if (uuid != null) serviceUuidByPath[ps] = uuid
                }
                val charsByService = HashMap<Uuid, MutableList<GattCharacteristic>>()
                managed.forEach { (p, ifaces) ->
                    val ps = p.toString()
                    if (!ps.startsWith(prefix)) return@forEach
                    val ch = ifaces[GATT_CHARACTERISTIC1] ?: return@forEach
                    val charUuid = (unwrapVariant(ch["UUID"]) as? String)?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                        ?: return@forEach
                    val svcPath = unwrapVariant(ch["Service"])?.toString() ?: return@forEach
                    val svcUuid = serviceUuidByPath[svcPath] ?: return@forEach
                    val flags = when (val f = unwrapVariant(ch["Flags"])) {
                        is Array<*> -> f.filterIsInstance<String>()
                        is List<*> -> f.filterIsInstance<String>()
                        else -> emptyList()
                    }
                    newCharPaths[svcUuid to charUuid] = ps
                    val propsBits = flagsToProperties(flags)
                    charsByService.getOrPut(svcUuid) { mutableListOf() }
                        .add(GattCharacteristic(uuid = charUuid, properties = propsBits, permissions = propsBits, descriptors = emptyList()))
                }
                charsByService.forEach { (svc, chars) -> out.add(GattService(svc, chars)) }
                charPaths = newCharPaths
                _services = out
                logger.d { "discovered ${out.size} services, ${newCharPaths.size} characteristics" }
            } catch (e: Exception) {
                logger.e("service discovery failed", e)
            }
            lastDiscoverMs = System.currentTimeMillis()
        }
    }

    override suspend fun discoverServices(): Boolean = _services?.isNotEmpty() == true

    private fun charPathFor(serviceUuid: Uuid, characteristicUuid: Uuid): String? {
        fun lookup(): String? = charPaths[serviceUuid to characteristicUuid]
            ?: charPaths.entries.firstOrNull { it.key.second == characteristicUuid }?.value
        lookup()?.let { return it }
        // Characteristics gated behind bonding/encryption only appear in BlueZ's object tree after
        // pairing completes; re-enumerate on a miss (throttled) so they're picked up post-bond.
        if (System.currentTimeMillis() - lastDiscoverMs > 1000) {
            logger.d { "characteristic $characteristicUuid not cached — re-discovering" }
            discover()
        }
        return lookup()
    }

    override fun subscribeToCharacteristic(serviceUuid: Uuid, characteristicUuid: Uuid): Flow<ByteArray>? {
        val path = charPathFor(serviceUuid, characteristicUuid) ?: run {
            // Some optional characteristics (e.g. connection params) are absent on certain firmwares;
            // the upper layers tolerate this, so keep it quiet.
            logger.d { "subscribe: characteristic not found: $characteristicUuid" }
            return null
        }
        return callbackFlow {
            val rule = DBusMatchRuleBuilder.create()
                .withType("signal").withInterface(DBUS_PROPERTIES)
                .withMember("PropertiesChanged").withPath(path).build()
            val handle = conn.addGenericSigHandler(rule) { msg: DBusSignal ->
                try {
                    val params = msg.getParameters() ?: return@addGenericSigHandler
                    if (params.size < 2 || params[0] != GATT_CHARACTERISTIC1) return@addGenericSigHandler
                    val changed = params[1] as? Map<*, *> ?: return@addGenericSigHandler
                    val bytes = asByteArray(unwrapVariant(changed["Value"])) ?: return@addGenericSigHandler
                    trySend(bytes)
                } catch (e: Exception) {
                    logger.v(e) { "notify handler error" }
                }
            }
            val charObj = conn.getRemoteObject(ORG_BLUEZ, path, BluezGattCharacteristic1Client::class.java)
            try { charObj.StartNotify() } catch (e: Exception) { logger.w { "StartNotify failed: ${e.message}" } }
            awaitClose {
                try { charObj.StopNotify() } catch (_: Exception) {}
                try { handle.close() } catch (_: Exception) {}
            }
        }
    }

    override suspend fun isBonded(): Boolean =
        io.rebble.libpebblecommon.connection.bt.isBonded(identifier)

    override suspend fun writeCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        value: ByteArray,
        writeType: GattWriteType,
    ): Boolean {
        val path = charPathFor(serviceUuid, characteristicUuid) ?: run {
            logger.d { "write: characteristic not found: $characteristicUuid" }
            return false
        }
        return try {
            val charObj = conn.getRemoteObject(ORG_BLUEZ, path, BluezGattCharacteristic1Client::class.java)
            val type = if (writeType == GattWriteType.NoResponse) "command" else "request"
            charObj.WriteValue(value, mapOf("type" to Variant(type)))
            true
        } catch (e: Exception) {
            logger.v(e) { "writeCharacteristic failed" }
            false
        }
    }

    override suspend fun readCharacteristic(serviceUuid: Uuid, characteristicUuid: Uuid): ByteArray? {
        val path = charPathFor(serviceUuid, characteristicUuid) ?: run {
            logger.d { "read: characteristic not found: $characteristicUuid" }
            return null
        }
        return try {
            val charObj = conn.getRemoteObject(ORG_BLUEZ, path, BluezGattCharacteristic1Client::class.java)
            charObj.ReadValue(emptyMap())
        } catch (e: Exception) {
            logger.v(e) { "readCharacteristic failed" }
            null
        }
    }

    override suspend fun requestMtu(mtu: Int): Int = negotiatedMtu()
    override suspend fun getMtu(): Int = negotiatedMtu()

    /** BlueZ negotiates the ATT MTU automatically and exposes it on GattCharacteristic1.MTU (≥5.62). */
    private fun negotiatedMtu(): Int {
        for (path in charPaths.values) {
            try {
                val props = conn.getRemoteObject(ORG_BLUEZ, path, Properties::class.java)
                val mtu = (unwrapVariant(props.Get<Any>(GATT_CHARACTERISTIC1, "MTU")) as? Number)?.toInt()
                if (mtu != null && mtu > 0) return mtu
            } catch (_: Exception) {}
        }
        logger.w { "MTU property unavailable; falling back to $DEFAULT_ATT_MTU" }
        return DEFAULT_ATT_MTU
    }

    override fun close() {
        // The DBusConnection is owned by BluezGattConnector; notify subscriptions clean themselves up
        // via their awaitClose blocks when their collecting scope is cancelled.
        logger.d { "close()" }
    }
}

/** Map BlueZ GattCharacteristic1 Flags strings to the standard GATT property bitmask. */
private fun flagsToProperties(flags: List<String>): Int {
    var bits = 0
    for (f in flags) {
        bits = bits or when (f) {
            "broadcast" -> 0x01
            "read" -> 0x02
            "write-without-response" -> 0x04
            "write" -> 0x08
            "notify" -> 0x10
            "indicate" -> 0x20
            "authenticated-signed-writes" -> 0x40
            "extended-properties" -> 0x80
            else -> 0
        }
    }
    return bits
}
