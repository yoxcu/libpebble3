package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Advertisement
import com.juul.kable.Identifier
import com.juul.kable.Scanner
import io.rebble.libpebblecommon.connection.BleScanResult
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

// JVM/Linux uses a pure-BlueZ D-Bus scanner (see BluezBleScanner) instead of kable/btleplug, which
// requires a glibc-only native lib. JvmKableBleScanner below is retained but unused.
actual fun kableBleScanner(): BleScanner = BluezBleScanner()

internal actual fun createKableAdvertisementsFlow(): Flow<Advertisement> =
    Scanner { }.advertisements

actual fun Identifier.asPebbleBleIdentifier(): PebbleBleIdentifier =
    PebbleBleIdentifier(toString()).also { it.kableIdentifier = this }

private class JvmKableBleScanner : BleScanner {
    override fun scan(): Flow<BleScanResult> =
        createKableAdvertisementsFlow()
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
