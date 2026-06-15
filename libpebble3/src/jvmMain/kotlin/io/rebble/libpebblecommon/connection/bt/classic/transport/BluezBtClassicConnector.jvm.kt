package io.rebble.libpebblecommon.connection.bt.classic.transport

import co.touchlab.kermit.Logger
import io.ktor.utils.io.writeByteArray
import io.rebble.libpebblecommon.connection.ConnectionFailureReason
import io.rebble.libpebblecommon.connection.PebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.PebbleProtocolStreams
import io.rebble.libpebblecommon.connection.bt.classic.pebble.BtClassicConnector
import io.rebble.libpebblecommon.connection.bt.classic.pebble.ClassicConnectionResult
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * JVM/Linux Bluetooth Classic connector — the BlueZ port of [AndroidBtClassicConnector]. Dials the
 * watch's SPP server over a secure RFCOMM socket ([BluezRfcommSocket]) and pumps the raw byte stream
 * straight into the Pebble protocol layer (no PPoGATT wrapper — over Classic the RFCOMM stream *is*
 * the Pebble protocol transport).
 *
 * Auto-pairs if the watch isn't BR/EDR-bonded (the agent auto-confirms host-side; user taps the watch)
 * and resolves the SPP RFCOMM channel via SDP, falling back to [PebbleBtClassicIdentifier.rfcommChannel].
 */
class BluezBtClassicConnector(
    private val identifier: PebbleBtClassicIdentifier,
    private val pebbleProtocolStreams: PebbleProtocolStreams,
    private val connectionCoroutineScope: ConnectionCoroutineScope,
) : BtClassicConnector {
    private val logger = Logger.withTag("BluezBtClassicConnector/${identifier.macAddress}")
    private val _disconnected = CompletableDeferred<ConnectionFailureReason>()
    override val disconnected: Deferred<ConnectionFailureReason> = _disconnected

    @Volatile private var socket: BluezRfcommSocket? = null

    override suspend fun connect(): ClassicConnectionResult {
        // The watch must already be BR/EDR-bonded (the daemon pairs up-front). Resolve the SPP channel
        // via SDP once (needs the watch reachable); fall back to the configured/identifier channel.
        val sdpChannel = withContext(Dispatchers.IO) { BluezRfcommSocket.resolveSppChannel(identifier.macAddress) }
        val channel = sdpChannel ?: identifier.rfcommChannel
        logger.i {
            "connecting RFCOMM to ${identifier.macAddress} channel $channel " +
                (if (sdpChannel != null) "(SDP-resolved)" else "(fallback)")
        }

        // Standing connection loop. BR/EDR has no kernel background auto-connect (that's BLE-only), so we
        // page the watch ourselves and retry with backoff until it's reachable — but QUIETLY (one INFO
        // when it goes out of range, DEBUG thereafter), so an out-of-range watch isn't noisy. (Returning
        // Failure per attempt made WatchManager re-spawn the connector and log an ERROR every cycle.)
        // Mirrors BluezGattConnector's standing-intent model; loop ends only when the scope is cancelled
        // (BT off / requestDisconnection / connect another watch / forget).
        var attempt = 0
        var away = false
        while (connectionCoroutineScope.isActive && !_disconnected.isCompleted) {
            val sock = try {
                withContext(Dispatchers.IO) { BluezRfcommSocket.connect(identifier.macAddress, channel) }
            } catch (e: Exception) {
                null
            }
            if (sock != null) {
                socket = sock
                logger.i { if (away) "RFCOMM reconnected" else "RFCOMM connected" }
                startPumps(sock)
                return ClassicConnectionResult.Success
            }
            attempt++
            if (!away) {
                logger.i { "${identifier.macAddress} not reachable (out of range?) — retrying quietly until it returns" }
                away = true
            } else {
                logger.d { "rfcomm connect retry $attempt failed" }
            }
            // Back off to keep airtime/noise low, but stay responsive to cancellation within ~2s.
            val backoffSecs = when {
                attempt <= 4 -> 5
                attempt <= 12 -> 15
                else -> 30
            }
            var waited = 0
            while (waited < backoffSecs && connectionCoroutineScope.isActive && !_disconnected.isCompleted) {
                delay(2.seconds)
                waited += 2
            }
        }
        if (!_disconnected.isCompleted) _disconnected.complete(ConnectionFailureReason.ClassicConnectionFailed)
        return ClassicConnectionResult.Failure
    }

    private fun startPumps(sock: BluezRfcommSocket) {
        // Inbound: socket → Pebble protocol. A read returning <=0 means the link closed.
        connectionCoroutineScope.launch(Dispatchers.IO) {
            val data = ByteArray(1024)
            try {
                while (true) {
                    val n = sock.read(data)
                    if (n <= 0) throw IllegalStateException("rfcomm stream ended (read=$n)")
                    pebbleProtocolStreams.inboundPPBytes.writeByteArray(data.copyOf(n))
                    pebbleProtocolStreams.inboundPPBytes.flush()
                }
            } catch (e: Exception) {
                logger.i { "inbound rfcomm ended: ${e.message}" }
                if (!_disconnected.isCompleted) _disconnected.complete(ConnectionFailureReason.ClassicDisconnected)
            }
        }
        // Outbound: Pebble protocol → socket.
        connectionCoroutineScope.launch {
            try {
                pebbleProtocolStreams.outboundPPBytes.consumeAsFlow().collect { bytes ->
                    withContext(Dispatchers.IO) { sock.write(bytes) }
                }
            } catch (e: Exception) {
                logger.i { "outbound rfcomm ended: ${e.message}" }
                if (!_disconnected.isCompleted) _disconnected.complete(ConnectionFailureReason.ClassicDisconnected)
            }
        }
    }

    override suspend fun disconnect() {
        logger.d { "disconnect()" }
        if (!_disconnected.isCompleted) _disconnected.complete(ConnectionFailureReason.ClassicDisconnected)
        socket?.close()
    }
}
