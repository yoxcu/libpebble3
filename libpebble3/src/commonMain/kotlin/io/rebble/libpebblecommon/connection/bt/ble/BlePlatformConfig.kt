package io.rebble.libpebblecommon.connection.bt.ble

import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.DEFAULT_MTU
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_RX_WINDOW
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.MAX_TX_WINDOW
import kotlin.time.Duration

data class BlePlatformConfig(
    val pinAddress: Boolean = true,
    val phoneRequestsPairing: Boolean = true,
    val writeConnectivityTrigger: Boolean = true,
    val initialMtu: Int = DEFAULT_MTU,
    val desiredTxWindow: Int = MAX_TX_WINDOW,
    val desiredRxWindow: Int = MAX_RX_WINDOW,
    val useNativeMtu: Boolean = true,
    val sendPpogResetOnDisconnection: Boolean = false,
    val delayBleConnectionsAfterAppStart: Boolean = false,
    val delayBleDisconnections: Boolean = true,
    val fallbackToResetRequest: Boolean = false,
    val closeGattServerWhenBtDisabled: Boolean = true,
    val supportsBtClassic: Boolean = false,
    val supportsPpogResetCharacteristic: Boolean = false,
    val supportsGattAutoConnect: Boolean = false,
    /** iOS bluetoothd can stall the write-without-response readiness signal for
     *  ~5s (MOB-9394), wedging Kable's write() before any bytes are dispatched.
     *  When set, WithoutResponse writes time out after this and re-issue. */
    val writeWithoutResponseStallTimeout: Duration? = null,
    /** Register the device with the forward-PPoG GATT server (which publishes the PPoG service on
     *  first use) BEFORE connecting, not after service discovery, whenever reversed PPoG V2 is off.
     *  For stacks where the watch starts forward PPoG (META read, RESET_REQUEST) before connect()
     *  returns: on BlueZ that is seconds before ServicesResolved. */
    val registerForwardPpogBeforeConnect: Boolean = false,
)