package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.CONNECTION_PARAMETERS_CHARACTERISTIC
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PAIRING_SERVICE_UUID
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean

// Once per run: the characteristic is a property of the watch firmware, and every reconnect would repeat it.
private val noCharacteristicLogged = AtomicBoolean(false)

class ConnectionParams(private val scope: ConnectionCoroutineScope) {
    private val logger = Logger.withTag("ConnectionParams")

    suspend fun subscribeAndConfigure(gattClient: ConnectedGattClient): Boolean {
        // TODO scope this
        val sub = gattClient.subscribeToCharacteristic(PAIRING_SERVICE_UUID, CONNECTION_PARAMETERS_CHARACTERISTIC)
        if (sub == null) {
            if (noCharacteristicLogged.compareAndSet(false, true)) {
                logger.d { "watch has no PPS Connection Parameters characteristic (Core firmware) — the watch manages the link parameters itself" }
            }
            return false
        }
        scope.launch {
            sub.collect {
                logger.d { "connection params changed: ${it.joinToString()}" }
            }
        }
        // Fork (stoandl): no "the phone manages" ({0x00, 0x01}) write. A Linux central never updates the
        // parameters itself, so the link would keep its connect-time ones, often the watch's 15 ms set.
        return true
    }
}