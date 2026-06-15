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
 * MVP: assumes the watch is already BR/EDR-bonded (a persistent [LinkKey]; pair it once with
 * `btmgmt pair -t bredr`). Auto-pairing/SDP-channel-discovery are deliberately left for a follow-up;
 * the RFCOMM channel comes from [PebbleBtClassicIdentifier.rfcommChannel].
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
        logger.i { "connect() RFCOMM to ${identifier.macAddress} channel ${identifier.rfcommChannel}" }
        val sock = try {
            withContext(Dispatchers.IO) {
                BluezRfcommSocket.connect(identifier.macAddress, identifier.rfcommChannel)
            }
        } catch (e: Exception) {
            logger.w(e) { "RFCOMM connect failed (is the watch BR/EDR-bonded and in range?)" }
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
