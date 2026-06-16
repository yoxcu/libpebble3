package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Advertisement
import com.juul.kable.Identifier
import com.juul.kable.Scanner
import io.rebble.libpebblecommon.BleConfig
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import kotlinx.coroutines.flow.Flow

// JVM/Linux uses a pure-BlueZ D-Bus scanner (see BluezBleScanner) instead of kable/btleplug, which
// requires a glibc-only native lib. It filters on the Pebble manufacturer IDs itself, so
// bleConfigFlow (filterScanResultsByUuid) isn't consulted. The createKableAdvertisementsFlow()/
// asPebbleBleIdentifier() actuals below only exist to satisfy the multiplatform expect
// declarations; they're unused on JVM.
actual fun kableBleScanner(bleConfigFlow: BleConfigFlow): BleScanner = BluezBleScanner()

internal actual fun createKableAdvertisementsFlow(bleConfig: BleConfig): Flow<Advertisement> =
    Scanner { }.advertisements

actual fun Identifier.asPebbleBleIdentifier(): PebbleBleIdentifier =
    PebbleBleIdentifier(toString()).also { it.kableIdentifier = this }

actual fun configureKableCentral(stateRestoration: Boolean) {
    // No-op: CoreBluetooth state restoration is iOS-only.
}
