package io.rebble.libpebblecommon.plugin

import kotlinx.coroutines.flow.Flow

/**
 * The phone's connectivity as the host reads it (stoandl: NetworkManager / ModemManager), for
 * [PhoneStatePlugin]. [cellGeneration] is one of [CellGenerations], [connection] one of
 * [Connections]; each emits null when the host can't say (no modem, say).
 */
actual class PhoneNetworkMonitor(
    actual val cellGeneration: Flow<String?>,
    actual val connection: Flow<String?>,
)
