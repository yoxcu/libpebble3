package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Advertisement
import com.juul.kable.Identifier
import com.juul.kable.Scanner
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import kotlinx.coroutines.flow.Flow

// JVM/Linux uses a pure-BlueZ D-Bus scanner (see BluezBleScanner) instead of kable/btleplug, which
// requires a glibc-only native lib. The createKableAdvertisementsFlow()/asPebbleBleIdentifier()
// actuals below only exist to satisfy the multiplatform expect declarations; they're unused on JVM.
actual fun kableBleScanner(): BleScanner = BluezBleScanner()

internal actual fun createKableAdvertisementsFlow(): Flow<Advertisement> =
    Scanner { }.advertisements

actual fun Identifier.asPebbleBleIdentifier(): PebbleBleIdentifier =
    PebbleBleIdentifier(toString()).also { it.kableIdentifier = this }
