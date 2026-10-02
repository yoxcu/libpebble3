package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.BleConnParamSet
import io.rebble.libpebblecommon.BleConnParams
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.WatchLinkActivity
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.CONNECTION_PARAMETERS_CHARACTERISTIC
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PAIRING_SERVICE_UUID
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoG
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattWriteType
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The Pebble Pairing Service "Connection Parameters" characteristic.
 *
 * Upstream writes `{0x00, 0x01}` ("the phone manages the parameters") and never touches it again. The
 * watch then never requests a change, and a Linux central never changes LE parameters on its own, so
 * the link keeps whatever it had when that write landed — often the watch's 15 ms bulk set from its
 * own early GATT discovery, i.e. ~67 connection events/s for the life of the link.
 *
 * Fork (stoandl): without configured sets nothing is written, so the watch keeps managing the link with
 * its own sets (idle 30–45 ms/latency 3, 15 ms while busy) — what every watch without this
 * characteristic (Core firmware) does anyway. Writing upstream's "phone manages" would freeze the link at
 * its connect-time parameters as soon as a firmware offers the characteristic again.
 *
 * Fork (stoandl, for hosts that keep the link up across system suspend): with
 * [io.rebble.libpebblecommon.BleConfig.connectionParams] set, the watch manages the parameters with
 * our sets instead. The idle profile puts the same [BleConnParams.idle] set in all three response-time
 * slots (MAX/MIDDLE/MIN): whatever latency the firmware wants, the desired parameters are already in
 * place, so after converging once it never sends another update request. That matters on a sleeping
 * phone because every request needs an answer from the host — a wake.
 *
 * Only if [BleConnParams.fast] is set, the link is boosted (fast set in the MIN slot plus the "desired
 * state = MIN" command) during the connect handshake and while PPoG moves bulk data, and returns to
 * the idle profile once quiet; see [manageBoost]. That is off by default because of a Linux trap: the
 * kernel rejects an LL "remote connection parameter request" whose max interval exceeds the max the
 * connection was *created* with (net/bluetooth/hci_event.c), and every accepted request is persisted
 * by bluetoothd as the device's parameters for the next connection. A link dropped while boosted is
 * therefore re-created at the fast interval and can never go slow again on the LL path — unless the
 * kernel compares against the adapter default instead (the "K5" patch) or the watch uses L2CAP
 * signalling. The watch's own 15 ms discovery request can cause the same trap on its own, which is
 * why the current parameters are logged and checked (see [checkIdleReached]).
 */
// Once per run: the characteristic is a property of the watch firmware, and every reconnect would repeat it.
@OptIn(ExperimentalAtomicApi::class)
private val noCharacteristicWarned = AtomicBoolean(false)

@OptIn(ExperimentalAtomicApi::class)
class ConnectionParams(
    private val scope: ConnectionCoroutineScope,
    private val bleConfig: BleConfigFlow,
    private val ppog: PPoG,
    private val linkActivity: WatchLinkActivity,
    private val identifier: PebbleIdentifier,
) {
    private val logger = Logger.withTag("ConnectionParams/${identifier.asString}")

    // Last parameters the watch reported (notification or read); null until the first report.
    @Volatile private var current: CurrentParams? = null

    suspend fun subscribeAndConfigure(gattClient: ConnectedGattClient): Boolean {
        // TODO scope this
        val sub = gattClient.subscribeToCharacteristic(PAIRING_SERVICE_UUID, CONNECTION_PARAMETERS_CHARACTERISTIC)
        if (sub == null) {
            // Core firmware (NimBLE) doesn't offer it: the watch then picks its own sets (PebbleOS
            // gap_le_connect_params.c) and the host accepts its update requests. Once per run is enough.
            if (noCharacteristicWarned.compareAndSet(false, true)) {
                if (bleConfig.value.connectionParams != null) {
                    logger.w { "this watch has no Connection Parameters characteristic — the configured connection parameters have no effect" }
                } else {
                    logger.d { "watch has no PPS Connection Parameters characteristic (Core firmware) — the watch manages the link parameters itself" }
                }
            }
            return false
        }
        scope.launch {
            sub.collect {
                Logger.d("connection params changed: ${it.joinToString()}")
                onReport(it)
            }
        }
        // No sets configured: leave the watch managing with its own (see the class comment).
        val params = bleConfig.value.connectionParams ?: return true
        val fast = params.fast
        val ok = if (fast != null) writeBoost(gattClient, params.idle, fast) else writeIdle(gattClient, params.idle)
        if (!ok) {
            logger.w { "writing the connection parameter sets failed — the watch keeps its own defaults" }
            return false
        }
        logger.i {
            "watch-managed connection parameters: idle ${params.idle}" +
                (fast?.let { ", fast $it during handshake/bulk" } ?: "")
        }
        if (fast != null) manageBoost(gattClient, params.idle, fast) else checkIdleReached(gattClient, params.idle)
        return true
    }

    /**
     * Boost policy (only with a fast set). Starts boosted — the watch's GATT discovery, the PPoG reset,
     * the negotiation and the initial BlobDB sync run fast — and returns to the idle profile once PPoG
     * has been quiet for [BOOST_QUIET], but not before [HANDSHAKE_BOOST] after connect. Boosts again when
     * PPoG moves at least [BULK_PACKETS] data packets within [BULK_WINDOW] (a firmware, language or app
     * transfer or a big sync — not a single notification). Never boosts while the host is suspending,
     * and drops a boost at once when it starts to, so the link never sleeps at the fast interval. While
     * boosted it reports one pending item to [WatchLinkActivity], so a host holding a sleep delay lock
     * waits for the idle write.
     */
    private fun manageBoost(gatt: ConnectedGattClient, idle: BleConnParamSet, fast: BleConnParamSet) {
        val source = "connparams:${identifier.asString}"
        scope.launch {
            val connectedAt = TimeSource.Monotonic.markNow()
            var boosted = true
            linkActivity.report(source, 1)
            val recent = ArrayDeque<Pair<TimeSource.Monotonic.ValueTimeMark, Long>>()
            try {
                merge(
                    ppog.dataPackets.map { LinkEvent.Traffic(it) },
                    linkActivity.hostSuspending.map { LinkEvent.Suspending(it) },
                ).collectLatest { event ->
                    val suspending = linkActivity.hostSuspending.value
                    if (event is LinkEvent.Traffic) {
                        recent.addLast(TimeSource.Monotonic.markNow() to event.packets)
                        while (recent.size > 1 && recent.first().first.elapsedNow() > BULK_WINDOW) recent.removeFirst()
                        val moved = event.packets - recent.first().second
                        if (!boosted && !suspending && moved >= BULK_PACKETS) {
                            // NonCancellable: the next packet restarts this block; a write cut off
                            // half-way would leave `boosted` stale and re-trigger on every packet.
                            withContext(NonCancellable) {
                                if (writeBoost(gatt, idle, fast)) {
                                    boosted = true
                                    linkActivity.report(source, 1)
                                    logger.i { "bulk transfer ($moved packets in ${BULK_WINDOW}) — boosted to $fast" }
                                }
                            }
                        }
                    }
                    if (!boosted) return@collectLatest
                    if (!suspending) {
                        val handshakeLeft = HANDSHAKE_BOOST - connectedAt.elapsedNow()
                        delay(maxOf(BOOST_QUIET, handshakeLeft))
                    }
                    withContext(NonCancellable) {
                        if (writeIdle(gatt, idle, dropMinVote = true)) {
                            boosted = false
                            linkActivity.report(source, 0)
                            logger.i { if (suspending) "host suspending — back to idle $idle" else "link quiet — back to idle $idle" }
                        }
                    }
                }
            } finally {
                linkActivity.report(source, 0)
            }
        }
    }

    /**
     * Without a fast set, the idle profile is written once. If the host rejects the watch's request the
     * watch gives up after three attempts and the link silently stays where it was, so read the actual
     * parameters back after [IDLE_CHECK_DELAY] and say so loudly — it is the one thing that makes this
     * feature a no-op (or worse, a link stuck at 15 ms).
     */
    private fun checkIdleReached(gatt: ConnectedGattClient, idle: BleConnParamSet) {
        scope.launch {
            delay(IDLE_CHECK_DELAY)
            gatt.readCharacteristic(PAIRING_SERVICE_UUID, CONNECTION_PARAMETERS_CHARACTERISTIC)?.let { onReport(it) }
            val now = current ?: return@launch
            if (now.matches(idle)) {
                logger.i { "link at idle parameters: $now" }
            } else {
                logger.w {
                    "link still at $now, not the idle set $idle: the host rejected the watch's update " +
                        "request. On Linux check /etc/bluetooth/main.conf [LE] MaxConnectionInterval " +
                        "(must be >= ${(idle.maxIntervalMs / 1.25).roundToInt()}) and the watch's stored " +
                        "[ConnectionParameters] in /var/lib/bluetooth/<adapter>/<watch>/info (a stored max " +
                        "below the idle max makes the kernel reject every slower request on the LL path)"
                }
            }
        }
    }

    private fun onReport(value: ByteArray) {
        val params = CurrentParams.parse(value) ?: return
        val previous = current
        current = params
        if (previous == null || previous != params) logger.i { "link parameters now $params" }
    }

    private suspend fun writeIdle(gatt: ConnectedGattClient, idle: BleConnParamSet, dropMinVote: Boolean = false): Boolean {
        // After a boost, drop our "desired state = MIN" vote first (best effort: firmware without the
        // command just logs it). Then give the watch nothing to switch to: one set in all three slots.
        if (dropMinVote) write(gatt, byteArrayOf(CMD_SET_REMOTE_DESIRED_STATE, RESPONSE_TIME_MAX))
        return write(gatt, paramMgmtWrite(idle, idle, idle))
    }

    private suspend fun writeBoost(gatt: ConnectedGattClient, idle: BleConnParamSet, fast: BleConnParamSet): Boolean =
        // Fast set in the MIN slot, then ask for MIN. The watch resets our vote after 5 minutes on its
        // own; manageBoost writes the idle profile long before that.
        write(gatt, paramMgmtWrite(idle, idle, fast)) &&
            write(gatt, byteArrayOf(CMD_SET_REMOTE_DESIRED_STATE, RESPONSE_TIME_MIN))

    private suspend fun write(gatt: ConnectedGattClient, value: ByteArray): Boolean =
        gatt.writeCharacteristic(PAIRING_SERVICE_UUID, CONNECTION_PARAMETERS_CHARACTERISTIC, value, GattWriteType.WithResponse)

    private sealed interface LinkEvent {
        data class Traffic(val packets: Long) : LinkEvent
        data class Suspending(val suspending: Boolean) : LinkEvent
    }

    /** `pbl_bt_pps_conn_params_read_notif`: the parameters currently in use on this link. */
    private data class CurrentParams(val intervalMs: Double, val slaveLatency: Int, val supervisionMs: Int) {
        // Same rule as PebbleOS prv_do_actual_params_match_desired_state for a non-MIN state.
        fun matches(set: BleConnParamSet): Boolean =
            intervalMs >= set.minIntervalMs && intervalMs <= set.maxIntervalMs && slaveLatency == set.slaveLatency

        override fun toString(): String = "interval ${intervalMs}ms/lat $slaveLatency/sup ${supervisionMs}ms"

        companion object {
            fun parse(v: ByteArray): CurrentParams? {
                if (v.size < 7) return null
                fun u16(i: Int) = (v[i].toInt() and 0xff) or ((v[i + 1].toInt() and 0xff) shl 8)
                return CurrentParams(intervalMs = u16(1) * 1.25, slaveLatency = u16(3), supervisionMs = u16(5) * 10)
            }
        }
    }

    companion object {
        private const val CMD_SET_REMOTE_PARAM_MGMT_SETTINGS: Byte = 0x00
        private const val CMD_SET_REMOTE_DESIRED_STATE: Byte = 0x01
        private const val RESPONSE_TIME_MAX: Byte = 0x00
        private const val RESPONSE_TIME_MIN: Byte = 0x02

        // Stay boosted at least this long after connect (discovery + PPoG reset + negotiation + sync).
        private val HANDSHAKE_BOOST = 20.seconds
        // Return to idle after this long without PPoG data packets.
        private val BOOST_QUIET = 5.seconds
        // Bulk detector: this many data packets (both directions) within the window. A notification is
        // a handful of packets; a 15-minute datalog flush a few dozen at most.
        private const val BULK_PACKETS = 64L
        private val BULK_WINDOW = 3.seconds
        private val IDLE_CHECK_DELAY = 60.seconds

        /**
         * `pbl_bt_pps_conn_params_write` with cmd SET_REMOTE_PARAM_MGMT_SETTINGS, flags 0 ("the watch
         * manages") and the three sets in the firmware's slot order MAX, MIDDLE, MIN — 17 bytes, within
         * the 20-byte minimum-MTU payload.
         */
        internal fun paramMgmtWrite(max: BleConnParamSet, middle: BleConnParamSet, min: BleConnParamSet): ByteArray =
            byteArrayOf(CMD_SET_REMOTE_PARAM_MGMT_SETTINGS, 0x00) + max.encode() + middle.encode() + min.encode()

        /** `pbl_bt_pps_conn_param_set`: u16 LE min interval (1.25 ms), u8 max-min delta (1.25 ms),
         *  u8 slave latency, u8 supervision timeout (30 ms — not the spec's 10 ms). */
        internal fun BleConnParamSet.encode(): ByteArray {
            val min = (minIntervalMs / 1.25).roundToInt()
            val delta = ((maxIntervalMs - minIntervalMs) / 1.25).roundToInt()
            val supervision = (supervisionTimeoutMs / 30.0).roundToInt()
            return byteArrayOf(
                (min and 0xff).toByte(), ((min shr 8) and 0xff).toByte(),
                delta.coerceIn(0, 255).toByte(),
                slaveLatency.coerceIn(0, 255).toByte(),
                supervision.coerceIn(1, 255).toByte(),
            )
        }
    }
}
