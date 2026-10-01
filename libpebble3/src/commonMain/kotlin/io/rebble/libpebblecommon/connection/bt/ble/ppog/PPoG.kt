package io.rebble.libpebblecommon.connection.bt.ble.ppog

import androidx.compose.ui.util.fastForEachReversed
import co.touchlab.kermit.Logger
import io.ktor.utils.io.writeByteArray
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.ConnectionException
import io.rebble.libpebblecommon.connection.ConnectionFailureReason
import io.rebble.libpebblecommon.connection.PebbleProtocolStreams
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

interface PPoGPacketSender {
    suspend fun sendPacket(packet: ByteArray): Boolean
    fun wasRestoredWithSubscribedCentral(): Boolean
}

class PPoGStream(val inboundPPoGBytesChannel: Channel<ByteArray> = Channel(capacity = 100))

class PPoG(
    private val pebbleProtocolStreams: PebbleProtocolStreams,
    private val pPoGStream: PPoGStream,
    private val pPoGPacketSender: PPoGPacketSender,
    private val bleConfig: BleConfigFlow,
    private val blePlatformConfig: BlePlatformConfig,
    private val scope: ConnectionCoroutineScope,
) {
    private val logger = Logger.withTag("PPoG")
    private var mtu: Int = blePlatformConfig.initialMtu
    private var closed = false

    // Fork (stoandl): link activity for hosts that suspend aggressively. [pendingPackets] is what the
    // phone still owes the watch on this link (queued + sent-but-unacked data packets); a host holding
    // a logind delay lock waits for it to reach 0 before letting the system sleep. [dataPackets] counts
    // data packets in both directions and drives ConnectionParams' bulk-transfer detector. PPoG lives
    // in the connection scope, so readers collect these in that scope and treat its end as "0 pending".
    private val _pendingPackets = MutableStateFlow(0)
    val pendingPackets: StateFlow<Int> = _pendingPackets.asStateFlow()
    private val _dataPackets = MutableStateFlow(0L)
    val dataPackets: StateFlow<Long> = _dataPackets.asStateFlow()

    fun run(reversed: Boolean = false) {
        logger.d("run(): ${if (reversed) "sending" else "waiting for"} PPoG RESET_REQUEST")
        scope.launch {
            val params = if (reversed) {
                // Reversed PPoG: the watch is server-side and, once the phone
                // subscribes to the notify characteristic, sits in
                // AwaitingResetRequest waiting for us to initiate. Skip the
                // initWaitingForResetRequest phase entirely.
                withTimeoutOrNull(12.seconds) { initWithResetRequest() }
            } else {
                // Forward PPoG waits for the watch to start the handshake, which it only does
                // once it has found our GATT server and subscribed. On BlueZ that can take longer
                // than upstream's 12s, which tore the connection down first. The Negotiator's 20s
                // timeout starts at the same moment (connect() returns right after run()), so it
                // is the effective cap: a watch that never sends a ResetRequest fails as
                // NegotiationFailed, and by the time this 30s runs out close() has already run
                // (so the fallbackToResetRequest window below never opens in practice).
                withTimeoutOrNull(30.seconds) {
                    initWaitingForResetRequest()
                } ?: withTimeoutOrNull(5.seconds) {
                    if (blePlatformConfig.fallbackToResetRequest && !closed) {
                        initWithResetRequest()
                    } else {
                        null
                    }
                }
            }
            if (params == null) {
                // A timeout after close() is the tail of a connection that already failed and is
                // being torn down: throwing then only logged a spurious ERROR, recorded a second
                // failure reason and started a second cleanup pass.
                if (closed) {
                    logger.d("PPoG init abandoned after close()")
                    return@launch
                }
                throw ConnectionException(ConnectionFailureReason.TimeoutInitializingPpog)
            }
            runConnection(params)
        }
    }

    private fun verboseLog(message: () -> String) {
        if (bleConfig.value.verbosePpogLogging) {
            logger.v(message = message)
        }
    }

    suspend fun close() {
        if (closed) return
        closed = true
        logger.d("close")
        if (blePlatformConfig.sendPpogResetOnDisconnection) {
            // This really for iOS, where the "connection" will stay alive when the app "disconnects",
            // but we need to get the watch's PPoG state machine into a "need to reconnect" state.
            try {
                sendPacketImmediately(PPoGPacket.ResetRequest(0, PPoGVersion.ONE), PPoGVersion.ONE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                // No PPoG sender (the link closed before PPoG was set up) or no link: nothing to reset.
                logger.d { "no PPoG reset on close: ${e.message}" }
            } catch (e: Exception) {
                logger.w("couldn't send PPoG reset on close", e)
            }
        }
    }

    private suspend inline fun <reified T : PPoGPacket> waitForPacket(): T {
        // Wait for reset request from watch
        while (true) {
            val packet = pPoGStream.inboundPPoGBytesChannel.receive().asPPoGPacket()
            if (packet !is T) {
                // Not expected, but can happen (i.e. don't crash out): if a watch reconnects
                // really quickly then we can see stale packets come through.
                logger.w("unexpected packet $packet waiting for ${T::class}")
                continue
            }
            return packet
        }
    }

    private suspend fun initWithResetRequest(): PPoGConnectionParams? {
        logger.d("initWithResetRequest")
        // Reversed PPoG doesn't have a meta characteristic, so we have to assume.
        val ppogVersion = PPoGVersion.ONE

        // Send reset request
        try {
            sendPacketImmediately(
                packet = PPoGPacket.ResetRequest(
                    sequence = 0,
                    ppogVersion = ppogVersion,
                ),
                version = ppogVersion,
            )
        } catch (e: CancellationException) {
            throw  e
        } catch (e: Exception) {
            logger.e("error sending reset request", e)
        }

        // The watch gives up on our reset request after a few seconds and starts its own. Answer
        // that instead of waiting for a ResetComplete that is never coming.
        while (true) {
            val packet = pPoGStream.inboundPPoGBytesChannel.receive().asPPoGPacket()
            when (packet) {
                is PPoGPacket.ResetComplete -> {
                    logger.d("got $packet")
                    sendResetComplete(ppogVersion)
                    return connectionParams(packet, ppogVersion)
                }

                is PPoGPacket.ResetRequest -> {
                    logger.d("got $packet while waiting for ResetComplete; responding")
                    return respondToResetRequest(packet)
                }

                else -> logger.w("unexpected packet $packet waiting for ResetComplete")
            }
        }
    }

    // Negotiate connection
    private suspend fun initWaitingForResetRequest(): PPoGConnectionParams {
        logger.d("initWaitingForResetRequest: waiting for RESET_REQUEST")

        val resetRequest = waitForPacket<PPoGPacket.ResetRequest>()
        logger.d("got $resetRequest")
        return respondToResetRequest(resetRequest)
    }

    private suspend fun respondToResetRequest(resetRequest: PPoGPacket.ResetRequest): PPoGConnectionParams {
        sendResetComplete(resetRequest.ppogVersion)

        // Wait for reset complete confirmation. The watch retries its ResetRequest if our
        // ResetComplete notification was not delivered (BLE notification dropped); re-send each time.
        while (true) {
            val packet = pPoGStream.inboundPPoGBytesChannel.receive().asPPoGPacket()
            when (packet) {
                is PPoGPacket.ResetComplete -> {
                    logger.d("got $packet")
                    return connectionParams(packet, resetRequest.ppogVersion)
                }

                is PPoGPacket.ResetRequest -> {
                    logger.d("re-got ResetRequest while waiting for ResetComplete - resending ResetComplete")
                    sendResetComplete(resetRequest.ppogVersion)
                }

                else -> logger.w("unexpected packet $packet waiting for ResetComplete")
            }
        }
    }

    private suspend fun sendResetComplete(version: PPoGVersion) {
        sendPacketImmediately(
            packet = PPoGPacket.ResetComplete(
                sequence = 0,
                rxWindow = min(blePlatformConfig.desiredRxWindow, MAX_SUPPORTED_WINDOW_SIZE),
                txWindow = min(blePlatformConfig.desiredTxWindow, MAX_SUPPORTED_WINDOW_SIZE),
            ),
            version = version,
        )
    }

    private fun connectionParams(
        resetComplete: PPoGPacket.ResetComplete,
        version: PPoGVersion,
    ) = PPoGConnectionParams(
        rxWindow = min(min(resetComplete.txWindow, blePlatformConfig.desiredTxWindow), MAX_SUPPORTED_WINDOW_SIZE),
        txWindow = min(min(resetComplete.rxWindow, blePlatformConfig.desiredRxWindow), MAX_SUPPORTED_WINDOW_SIZE),
        pPoGversion = version,
    )

    // No need for any locking - state is only accessed/mutated within this method (except for mtu
    // which can only increase).
    private suspend fun runConnection(params: PPoGConnectionParams) {
        logger.d("runConnection: $params")

        val outboundSequence = Sequence()
        val inboundSequence = Sequence()
        val outboundDataQueue = ArrayDeque<PacketToSend>()
        val inflightPackets = ArrayDeque<PacketToSend>()
        val onTimeout = Channel<Unit>()
        var timeoutJob: Job? = null
        var lastSentAck: PPoGPacket.Ack? = null
        var lastReceivedAck: PPoGPacket.Ack? = null

        fun cancelTimeout() {
            timeoutJob?.cancel()
            timeoutJob = null
        }

        fun rescheduleTimeout() {
            cancelTimeout()
            timeoutJob = scope.launch {
                delay(RESET_REQUEST_TIMEOUT)
                logger.w("Packet timeout")
                onTimeout.send(Unit)
                timeoutJob = null
            }
        }

        fun resendInflightPackets() {
            inflightPackets.fastForEachReversed { packet ->
                val resendPacket = packet.copy(attemptCount = packet.attemptCount + 1)
                if (resendPacket.attemptCount > MAX_NUM_RETRIES) {
                    logger.w("Exceeded max retries")
                    throw IllegalStateException("Exceeded max retries")
                }
                outboundDataQueue.addFirst(resendPacket)
            }
            inflightPackets.clear()
        }

        fun removeResendsUpTo(sequence: Int) {
            while (true) {
                val sendPacket = outboundDataQueue.firstOrNull()
                if (sendPacket == null || sendPacket.attemptCount == 0 || sendPacket.packet.sequence > sequence) {
                    break
                }
                outboundDataQueue.removeFirst()
            }
        }

        while (true) {
            if (closed) return
            select {
                onTimeout.onReceive {
                    resendInflightPackets()
                }
                pebbleProtocolStreams.outboundPPBytes.onReceive { bytes ->
                    bytes.asList().chunked(maxDataBytes())
                        .map { chunk ->
                            PacketToSend(
                                packet = PPoGPacket.Data(
                                    sequence = outboundSequence.getThenIncrement(),
                                    data = chunk.toByteArray()
                                ),
                                attemptCount = 0,
                            )
                        }
                        .forEach {
                            outboundDataQueue.addLast(it)
                            _dataPackets.value++
                        }
                }
                pPoGStream.inboundPPoGBytesChannel.onReceive { bytes ->
                    val packet = bytes.asPPoGPacket()
                    verboseLog { "received packet: $packet" }
                    when (packet) {
                        is PPoGPacket.Ack -> {
                            removeResendsUpTo(packet.sequence)
                            if (packet == lastReceivedAck) {
                                logger.w("Received duplicate ACK; resending inflight packets")
                                resendInflightPackets()
                            }
                            // TODO remove resends of this packet from send queue (+ also remove up-to-them, which OG code didn't do?)

                            // Remove from in-flight packets, up until (including) this packet
                            // TODO warn if we don't have that packet inflight?
                            while (true) {
                                val inflightPacket = inflightPackets.removeFirstOrNull() ?: break
                                if (inflightPacket.packet.sequence == packet.sequence) break
                            }
                            if (inflightPackets.isEmpty()) {
                                cancelTimeout()
                            }
                            lastReceivedAck = packet
                        }

                        is PPoGPacket.Data -> {
                            if (packet.sequence != inboundSequence.get() && lastSentAck != null) {
                                // Genuine mid-stream gap/reorder: re-ack our last in-order packet and
                                // drop this one (reliable-transport retransmit recovery).
                                logger.w("data out of sequence: got ${packet.sequence}, expected ${inboundSequence.get()}; resending ack ${lastSentAck?.sequence}")
                                lastSentAck?.let { sendPacketImmediately(it, params.pPoGversion) }
                            } else {
                                if (packet.sequence != inboundSequence.get()) {
                                    // No inbound baseline yet this session (lastSentAck == null). After the
                                    // reset handshake the watch should restart its data sequence at 0, but a
                                    // watch left in a "dirty" PPoG state by an abruptly-killed previous session
                                    // (e.g. a daemon restart) resumes at its old sequence — and we can't force
                                    // it to reset (PebbleOS doesn't expose the PPoG-reset characteristic 0x0006,
                                    // and nothing writes it since upstream dropped PPoGReset). The old code then
                                    // hit the branch above with lastSentAck == null, so "resending last ack" sent
                                    // nothing: the watch got no feedback, retransmitted forever, and the
                                    // connection dead-locked until a watchdog tore it down (~40s churn). Adopt
                                    // the first packet's sequence as our baseline instead — GATT notifications on
                                    // one characteristic are ordered, so the first packet after reset is
                                    // authoritative.
                                    logger.w("first inbound data seq=${packet.sequence} (expected ${inboundSequence.get()}); resyncing baseline")
                                    inboundSequence.set(packet.sequence)
                                }
                                pebbleProtocolStreams.inboundPPBytes.writeByteArray(packet.data)
                                pebbleProtocolStreams.inboundPPBytes.flush()
                                inboundSequence.increment()
                                _dataPackets.value++
                                // TODO coalesced ACKing
                                lastSentAck = PPoGPacket.Ack(sequence = packet.sequence)
                                    .also { sendPacketImmediately(it, params.pPoGversion) }
                            }
                        }

                        // Logged at WARN in-session: the watch sends these when its ack timeouts have
                        // run out, so they are the phone-side marker of a watch reset storm. After
                        // close() they are only the watch answering our close-time ResetRequest
                        // (sendPpogResetOnDisconnection): end the session quietly instead of throwing,
                        // which was just noise and triggered an extra cleanup pass.
                        is PPoGPacket.ResetComplete -> {
                            if (closed) {
                                logger.d("$packet after close(); ending PPoG session")
                            } else {
                                logger.w("in-session $packet from watch; tearing down PPoG session")
                                throw IllegalStateException("We don't handle resetting PPoG - disconnect and reconnect")
                            }
                        }

                        is PPoGPacket.ResetRequest -> {
                            if (closed) {
                                logger.d("$packet after close(); ending PPoG session")
                            } else {
                                logger.w("in-session $packet from watch; tearing down PPoG session")
                                throw IllegalStateException("We don't handle resetting PPoG - disconnect and reconnect")
                            }
                        }
                    }
                }
            }

            // Drain send queue
            while (inflightPackets.size < params.txWindow && !outboundDataQueue.isEmpty()) {
                if (closed) return
                val packet = outboundDataQueue.removeFirst()
                sendPacketImmediately(packet.packet, params.pPoGversion)
                rescheduleTimeout()
                inflightPackets.add(packet)
            }
            _pendingPackets.value = outboundDataQueue.size + inflightPackets.size
        }
    }

    private fun maxDataBytes() = mtu - DATA_HEADER_OVERHEAD_BYTES

    private suspend fun sendPacketImmediately(packet: PPoGPacket, version: PPoGVersion) {
        if (packet is PPoGPacket.Data || packet is PPoGPacket.Ack) {
            verboseLog { "sendPacketImmediately: $packet" }
        } else {
            logger.d { "sendPacketImmediately: $packet" }
        }
        if (!pPoGPacketSender.sendPacket(packet.serialize(version))) {
            logger.e("Couldn't send packet!")
            throw IllegalStateException("Couldn't send packet!")
        }
    }

    fun updateMtu(mtu: Int) {
        if (mtu < this.mtu) throw IllegalStateException("Can't reduce MTU")
        this.mtu = mtu
    }
}

private const val DATA_HEADER_OVERHEAD_BYTES = 1 + 3
private const val MAX_SEQUENCE = 32
private const val MAX_NUM_RETRIES = 2
private val RESET_REQUEST_TIMEOUT = 10.seconds

private data class PPoGConnectionParams(
    val rxWindow: Int,
    val txWindow: Int,
    val pPoGversion: PPoGVersion,
)

private data class PacketToSend(
    val packet: PPoGPacket.Data,
    val attemptCount: Int,
)

private class Sequence {
    private var sequence = 0

    fun getThenIncrement(): Int {
        val currentSequence = sequence
        increment()
        return currentSequence
    }

    fun get(): Int = sequence

    fun set(value: Int) {
        sequence = value % MAX_SEQUENCE
    }

    fun increment() {
        sequence = (sequence + 1) % MAX_SEQUENCE
    }
}
