package io.rebble.libpebblecommon.connection

import com.juul.kable.Peripheral
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.createBlePlatformIdentifier

sealed class PlatformIdentifier {
    // peripheral is nullable: the JVM/Linux (BlueZ) transport doesn't use a kable peripheral.
    data class BlePlatformIdentifier(val peripheral: Peripheral?, val autoConnect: Boolean = false) : PlatformIdentifier()
    data class SocketPlatformIdentifier(val addr: String) : PlatformIdentifier()
    data class BtClassicPlatformIdentifier(val identifier: PebbleBtClassicIdentifier) : PlatformIdentifier()
}


interface CreatePlatformIdentifier {
    fun identifier(
        identifier: PebbleIdentifier,
        name: String,
        lastAttemptFailed: Boolean,
    ): PlatformIdentifier?
}

class RealCreatePlatformIdentifier(
    private val bleConfig: BleConfigFlow,
    private val blePlatformConfig: BlePlatformConfig,
) : CreatePlatformIdentifier {
    override fun identifier(
        identifier: PebbleIdentifier,
        name: String,
        lastAttemptFailed: Boolean,
    ): PlatformIdentifier? = when (identifier) {
        is PebbleBleIdentifier -> {
            val autoConnect = blePlatformConfig.supportsGattAutoConnect &&
                    lastAttemptFailed && bleConfig.value.autoConnectAfterFailure
            createBlePlatformIdentifier(identifier, name, autoConnect)
        }

        is PebbleBtClassicIdentifier -> PlatformIdentifier.BtClassicPlatformIdentifier(identifier)

        is PebbleSocketIdentifier -> PlatformIdentifier.SocketPlatformIdentifier(identifier.address)

        else -> error("unknown identifier type: $identifier")
    }
}
