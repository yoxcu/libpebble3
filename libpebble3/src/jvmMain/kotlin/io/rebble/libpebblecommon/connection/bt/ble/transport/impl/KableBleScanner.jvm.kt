package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Advertisement
import com.juul.kable.Identifier
import com.juul.kable.Scanner
import io.rebble.libpebblecommon.BleConfig
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.BleScanResult
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

actual fun kableBleScanner(bleConfigFlow: BleConfigFlow): BleScanner = JvmKableBleScanner(bleConfigFlow)

internal actual fun createKableAdvertisementsFlow(bleConfig: BleConfig): Flow<Advertisement> =
    Scanner { }.advertisements

actual fun Identifier.asPebbleBleIdentifier(): PebbleBleIdentifier =
    PebbleBleIdentifier(toString()).also { it.kableIdentifier = this }

actual fun configureKableCentral(stateRestoration: Boolean) {
    // No-op: CoreBluetooth state restoration is iOS-only.
}

private class JvmKableBleScanner(private val bleConfigFlow: BleConfigFlow) : BleScanner {
    override fun scan(): Flow<BleScanResult> =
        createKableAdvertisementsFlow(bleConfigFlow.value)
            .filter { it.manufacturerData != null }
            .map { adv ->
                BleScanResult(
                    identifier = adv.identifier.asPebbleBleIdentifier(),
                    name = adv.name ?: "",
                    rssi = adv.rssi,
                    manufacturerData = adv.manufacturerData!!,
                )
            }
}
