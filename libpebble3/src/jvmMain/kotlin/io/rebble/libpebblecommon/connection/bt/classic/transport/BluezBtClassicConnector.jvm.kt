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
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // The watch must already be BR/EDR-bonded — pairing is done up-front (outside this connect
        // attempt) by the daemon, because a blocking Pair() (~10s, user taps the watch) races the
        // connection-attempt timeout. Here we just open the RFCOMM data link.
        // Resolve the SPP RFCOMM channel via SDP; fall back to the configured/identifier channel.
        val sdpChannel = withContext(Dispatchers.IO) { resolveSppChannel(identifier.macAddress) }
        val primary = sdpChannel ?: identifier.rfcommChannel
        logger.i {
            "connect() RFCOMM to ${identifier.macAddress} channel $primary " +
                (if (sdpChannel != null) "(SDP-resolved)" else "(fallback)")
        }
        var sock = try {
            withContext(Dispatchers.IO) { BluezRfcommSocket.connect(identifier.macAddress, primary) }
        } catch (e: Exception) {
            logger.w { "RFCOMM connect on channel $primary failed: ${e.message}" }
            null
        }
        // If the SDP-resolved channel failed, try the configured fallback channel once.
        if (sock == null && primary != identifier.rfcommChannel) {
            logger.i { "retrying RFCOMM on fallback channel ${identifier.rfcommChannel}" }
            sock = try {
                withContext(Dispatchers.IO) { BluezRfcommSocket.connect(identifier.macAddress, identifier.rfcommChannel) }
            } catch (e: Exception) {
                logger.w { "RFCOMM connect on fallback channel failed: ${e.message}" }
                null
            }
        }
        if (sock == null) {
            logger.w { "RFCOMM connect failed (is the watch BR/EDR-bonded and in range?)" }
            if (!_disconnected.isCompleted) _disconnected.complete(ConnectionFailureReason.ClassicConnectionFailed)
            return ClassicConnectionResult.Failure
        }
        socket = sock
        logger.i { "RFCOMM connected" }

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
        return ClassicConnectionResult.Success
    }

    override suspend fun disconnect() {
        logger.d { "disconnect()" }
        if (!_disconnected.isCompleted) _disconnected.complete(ConnectionFailureReason.ClassicDisconnected)
        socket?.close()
    }
}
