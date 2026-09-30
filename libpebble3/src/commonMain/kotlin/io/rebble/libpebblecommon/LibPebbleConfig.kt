package io.rebble.libpebblecommon

import androidx.annotation.VisibleForTesting
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class LibPebbleConfigHolder(
    private val defaultValue: LibPebbleConfig,
    private val settings: Settings,
    private val json: Json,
) {
    private fun defaultValue(): LibPebbleConfig {
        return loadFromStorage() ?: defaultValue.also { saveToStorage(it) }
    }

    private fun loadFromStorage(): LibPebbleConfig? = settings.getStringOrNull(SETTINGS_KEY)?.let { string ->
        try {
            json.decodeFromString(string)
        } catch (e: SerializationException) {
            Logger.w("Error loading settings", e)
            null
        }
    }

    private fun saveToStorage(value: LibPebbleConfig) {
        settings.set(SETTINGS_KEY, json.encodeToString(value))
    }

    fun update(value: LibPebbleConfig) {
        saveToStorage(value)
        _config.value = value
    }

    private val _config: MutableStateFlow<LibPebbleConfig> = MutableStateFlow(defaultValue())
    val config: StateFlow<LibPebbleConfig> = _config.asStateFlow()
}

private const val SETTINGS_KEY = "libpebble.settings"

@Serializable
data class LibPebbleConfig(
    val bleConfig: BleConfig = BleConfig(),
    val watchConfig: WatchConfig = WatchConfig(),
    val notificationConfig: NotificationConfig = NotificationConfig(),
)

class LibPebbleConfigFlow(val flow: StateFlow<LibPebbleConfig>) {
    val value get() = flow.value
}

@Serializable
data class WatchConfig(
    val multipleConnectedWatchesSupported: Boolean = false,
    val lockerSyncLimitV2: Int = 50,
    val calendarPins: Boolean = true,
    val calendarReminders: Boolean = true,
    val calendarShowDeclinedEvents: Boolean = false,
    /**
     * Name of the vibe pattern used for every calendar reminder; the watch's own default when null.
     */
    val overrideCalendarVibePattern: String? = null,
    val ignoreMissingPrf: Boolean = false,
    val lanDevConnection: Boolean = false,
    val verboseWatchManagerLogging: Boolean = false,
    val autoResumeFirmwareUpdate: Boolean = true,
    val pkjsInspectable: Boolean = false,
    val emulateRemoteTimeline: Boolean = true,
    /**
     * When true, LibPebble3 will always send music state as paused, never as playing.
     *
     * This prevents music app from jumping to the top of the list.
     */
    val alwaysSendMusicPaused: Boolean = false,
    /**
     * Do AppMessages get delivered to both PKJS and (android) PebbleKit companion apps?
     */
    val appMessageToMultipleCompanions: Boolean = true,
    val orderWatchfacesByLastUsed: Boolean = false,
    val unknownWatchTypePlatform: WatchType = WatchType.EMERY,
    /**
     * When true, BLE scan results include legacy classic-supporting Pebbles
     * (Aplite/Basalt/Chalk). By default these are hidden so users go through the dedicated
     * Bluetooth Classic scan instead.
     */
    val allowLegacyWatchesInBleScan: Boolean = false,
    /**
     * Intended to be a debug option to dsiable watch settings sync.
     */
    val enableWatchSettingsSync: Boolean = true,
    val musicSeekWhenAvailable: Boolean = true,
    val enablePlugins: Boolean = false,
)

class WatchConfigFlow(val flow: StateFlow<LibPebbleConfig>) {
    val value: WatchConfig get() = flow.value.watchConfig
}

@VisibleForTesting
fun WatchConfig.asFlow() = WatchConfigFlow(MutableStateFlow(LibPebbleConfig(watchConfig = this)))

@Serializable
data class BleConfig(
    @SerialName("reversedPpog")
    val legacyReversedPPoG: Boolean = false,
    /**
     * When false, ignore any reversed PPoG service the watch advertises and host forward PPoG
     * ourselves instead. Evaluated per connection, so it takes effect on the next reconnect.
     */
    val useReversedPpogV2: Boolean = getPlatform() == PhoneAppVersion.OSType.Android,
    val verbosePpogLogging: Boolean = false,
    /**
     * iOS only. When true, re-publish the GATT services automatically after the BT stack
     * returns to PoweredOn following a state restoration.
     */
    val republishGattServicesOnRestore: Boolean = false,
    /**
     * Android only. After a failed connection attempt, use GATT autoConnect for subsequent
     * attempts, so that the OS waits for the watch to appear instead of us retrying in a loop.
     */
    val autoConnectAfterFailure: Boolean = true,
    /**
     * iOS only. Opt the watch connection into CoreBluetooth state preservation/restoration so the
     * OS can relaunch us for central-side events. Applied once during
     * [io.rebble.libpebblecommon.connection.LibPebble3.create], because Kable's central manager can
     * only be configured before first use — changing it takes effect on the next app launch.
     *
     * Also suppresses the system "Bluetooth is off" alert: Kable sets
     * CBCentralManagerOptionShowPowerAlertKey=false whenever it passes options at all.
     */
    val centralStateRestoration: Boolean = false,
    val filterScanResultsByUuid: Boolean = true,
    /**
     * Fork addition (stoandl, for hosts that suspend and keep the link up across it). `null` (the
     * default) keeps the upstream behaviour: tell the watch the phone manages the LE connection
     * parameters (`{0x00, 0x01}`), after which neither side ever changes them — the link stays at
     * whatever it had when that write landed. Non-null lets the watch manage them with *our* parameter
     * sets instead: [BleConnParams.idle] for all three response-time states, so it converges once and
     * never requests a change again (each request would need the host — a wake if it is asleep); and,
     * only if [BleConnParams.fast] is set, a temporary fast MIN set during the connect handshake and
     * bulk transfers. See ConnectionParams for the details and the Linux caveats.
     */
    val connectionParams: BleConnParams? = null,
)

/**
 * One LE connection-parameter set in the form PebbleOS's Pebble Pairing Service takes it
 * (`pbl_bt_pps_conn_param_set`): interval range, slave latency, supervision timeout.
 * Limits (checked by [validate]): finite, 7.5 ms ≤ min ≤ max ≤ 4 s, max − min ≤ 318.75 ms (one byte of
 * 1.25 ms steps), latency 0..255, supervision 100 ms..7.65 s (one byte of 30 ms steps) and longer
 * than 2 × (1 + latency) × max interval (the Bluetooth spec rule the Linux host also enforces).
 */
@Serializable
data class BleConnParamSet(
    val minIntervalMs: Double,
    val maxIntervalMs: Double,
    val slaveLatency: Int,
    val supervisionTimeoutMs: Int,
) {
    /** Human-readable problem with this set, or null when PebbleOS and the Linux host accept it. */
    fun validate(): String? = when {
        // NaN passes every comparison below, then fails encode()'s roundToInt() on every connect.
        !minIntervalMs.isFinite() || !maxIntervalMs.isFinite() -> "intervals must be numbers"
        minIntervalMs < 7.5 -> "min interval ${minIntervalMs}ms < 7.5ms"
        maxIntervalMs < minIntervalMs -> "max interval ${maxIntervalMs}ms < min ${minIntervalMs}ms"
        maxIntervalMs > 4000.0 -> "max interval ${maxIntervalMs}ms > 4000ms"
        (maxIntervalMs - minIntervalMs) / 1.25 > 255 -> "max - min > 318.75ms (does not fit the watch's one-byte delta)"
        slaveLatency !in 0..255 -> "slave latency $slaveLatency outside 0..255"
        supervisionTimeoutMs !in 100..7650 -> "supervision ${supervisionTimeoutMs}ms outside 100..7650ms"
        supervisionTimeoutMs <= 2 * (1 + slaveLatency) * maxIntervalMs ->
            "supervision ${supervisionTimeoutMs}ms must exceed 2 x (1 + latency) x max interval"
        else -> null
    }

    override fun toString(): String =
        "${fmt(minIntervalMs)}-${fmt(maxIntervalMs)}ms/lat $slaveLatency/sup ${supervisionTimeoutMs}ms"

    private fun fmt(ms: Double): String = if (ms % 1.0 == 0.0) ms.toLong().toString() else ms.toString()
}

/** See [BleConfig.connectionParams]. */
@Serializable
data class BleConnParams(
    val idle: BleConnParamSet,
    val fast: BleConnParamSet? = null,
)

class BleConfigFlow(val flow: StateFlow<LibPebbleConfig>) {
    val value: BleConfig get() = flow.value.bleConfig
}

@VisibleForTesting
fun BleConfig.asFlow() = BleConfigFlow(MutableStateFlow(LibPebbleConfig(bleConfig = this)))

@Serializable
data class NotificationConfig(
    val dumpNotificationContent: Boolean = true,
    val obfuscateContent: Boolean = true,
    val sendLocalOnlyNotifications: Boolean = false,
    val storeNotifiationsForDays: Int = 7,
    val storeDisabledNotifications: Boolean = false,
    val addShowsUserInterfaceActions: Boolean = false,
    val alwaysSendNotifications: Boolean = true,
    /**
     * Mute all notification sounds on the phone when at least one watch is connected
     */
    val mutePhoneNotificationSoundsWhenConnected: Boolean = false,
    /**
     * Mute all call alerts on the phone when at least one watch is connected
     */
    val mutePhoneCallSoundsWhenConnected: Boolean = false,
    /**
     * When [true], any notifications muted by the phone's do not disturb will not be forwarded to the watch
     */
    val respectDoNotDisturb: Boolean = false,
    /**
     * Default new apps to be enabled (else they will be disabled).
     */
    val defaultAppsToEnabled: Boolean = true,
    /**
     * When [false], no notifications will be sent at all
     */
    val sendNotifications: Boolean = true,
    val sendNotificationImages: Boolean = true,
    val useAndroidVibePatterns: Boolean = false,
    val overrideDefaultVibePattern: String? = null,
    /**
     * User-defined canned responses appended to every reply action sent to the watch.
     * Shown under "Canned messages" in the watch action menu.
     */
    val cannedResponses: List<String> = listOf("Ok", "Yes", "No", "Call me", "Call you later"),
    /**
     * Fork addition (stoandl, for hosts whose watch link drops while notifications keep arriving — a
     * phone that loses the link on every suspend, a watch out of range). `0` (the default) keeps the
     * upstream behaviour: a connection only sends notifications created after its sync started, so
     * anything posted while the watch was away is never delivered. Non-zero: a (re)connecting watch also
     * gets the notifications it has not received that are at most this old — never ones from before
     * libpebble started, or from before the watch's last fresh start (first connection after pairing,
     * unfaithful watch). See NotificationCatchUp. Read at each connection.
     */
    val missedNotificationCatchUpMs: Long = 0,
)

class NotificationConfigFlow(val flow: StateFlow<LibPebbleConfig>) {
    val value: NotificationConfig get() = flow.value.notificationConfig
}

@VisibleForTesting
fun NotificationConfig.asFlow() = NotificationConfigFlow(MutableStateFlow(LibPebbleConfig(notificationConfig = this)))
