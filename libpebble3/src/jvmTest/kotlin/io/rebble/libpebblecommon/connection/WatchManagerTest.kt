package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.LibPebbleAnalytics
import io.rebble.libpebblecommon.NotificationConfig
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.asFlow
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.ble.pebble.BatteryWatcher
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.endpointmanager.LanguagePackInstallState
import io.rebble.libpebblecommon.connection.endpointmanager.LanguagePackInstaller
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.NotificationCatchUp
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.RealTimeProvider
import io.rebble.libpebblecommon.database.BlobDbDatabaseManager
import io.rebble.libpebblecommon.database.MillisecondInstant
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.TransportType
import io.rebble.libpebblecommon.di.ConnectionAnalyticsLogger
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.ConnectionScope
import io.rebble.libpebblecommon.di.ConnectionScopeFactory
import io.rebble.libpebblecommon.di.ConnectionScopeProperties
import io.rebble.libpebblecommon.di.HackyProvider
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.web.FirmwareUpdateManager
import com.russhwolf.settings.PropertiesSettings
import java.util.Properties
import io.rebble.libpebblecommon.web.LockerModel
import io.rebble.libpebblecommon.web.LockerModelWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.files.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class WatchManagerTest {
    // Watches present in the DB at WatchManager construction. Set before create().
    private var seededKnownWatches: List<KnownWatchItem> = emptyList()

    private val knownWatchDao = object : KnownWatchDao {
        override suspend fun insertOrUpdate(watch: KnownWatchItem) {
        }

        override suspend fun knownWatches(): List<KnownWatchItem> {
            return seededKnownWatches
        }

        override suspend fun remove(transportIdentifier: String) {
        }

        override suspend fun setNickname(identifier: String, nickname: String?) {
        }
    }
    private val pebbleDeviceFactory = PebbleDeviceFactory()
    private val createPlatformIdentifier = object : CreatePlatformIdentifier {
        override fun identifier(
            identifier: PebbleIdentifier,
            name: String,
            lastAttemptFailed: Boolean,
        ): PlatformIdentifier {
            return PlatformIdentifier.SocketPlatformIdentifier("addr")
        }
    }

    class TestConnectionScope(
        override val identifier: PebbleIdentifier,
        override val pebbleConnector: PebbleConnector,
        override val closed: AtomicBoolean = AtomicBoolean(false),
        override val firmwareUpdateManager: FirmwareUpdateManager,
        override val firmwareUpdater: FirmwareUpdater,
        override val batteryWatcher: BatteryWatcher,
        override val analyticsLogger: ConnectionAnalyticsLogger,
        override val usingBtClassic: Boolean,
        override val languagePackInstaller: LanguagePackInstaller,
    ) : ConnectionScope {
        override fun close() {
        }
    }

    private val identifier = "addr".asPebbleBleIdentifier()
    private val name = "name"

    private var activeConnections = 0
    private var totalConnections = 0
    private var connectSuccess = false
    private var exceededMax = false
    private var lastPreviouslyConnected: Boolean? = null
    // Fork (stoandl): a per-watch connector for the two-watch tests; null = TestPebbleConnector.
    private var connectorFor: ((PebbleIdentifier) -> PebbleConnector)? = null

    inner class TestPebbleConnector : PebbleConnector {
        private val _disconnected = CompletableDeferred<ConnectionFailureReason>()
        private val _state =
            MutableStateFlow<ConnectingPebbleState>(ConnectingPebbleState.Inactive(identifier))

        fun onDisconnection() {
            activeConnections--
            _disconnected.complete(ConnectionFailureReason.FailedToConnect)
        }

        override suspend fun connect(knownWatchProperties: KnownWatchProperties?, lastError: ConnectionFailureReason?) {
            lastPreviouslyConnected = knownWatchProperties?.lastConnected != null
            activeConnections++
            totalConnections++
            if (activeConnections > 1) {
                exceededMax = true
                throw IllegalStateException("too many active connections!")
            }
            _state.value = ConnectingPebbleState.Connecting(identifier)
            delay(1.milliseconds)
            _state.value = ConnectingPebbleState.Negotiating(identifier, null)
            delay(1.milliseconds)
            if (connectSuccess) {
                _state.value = ConnectingPebbleState.Connected.ConnectedNotInPrf(
                    identifier = identifier,
                    watchInfo = TODO(),
                    services = TODO(),
                    reversePpogVersion = null,
                )
            } else {
                _state.value = ConnectingPebbleState.Failed(identifier, ConnectionFailureReason.FailedToConnect)
                onDisconnection()
            }
        }

        override fun disconnect() {
            _state.value = ConnectingPebbleState.Inactive(identifier)
            onDisconnection()
        }

        override val disconnected: WasDisconnected = WasDisconnected(_disconnected)
        override val state: StateFlow<ConnectingPebbleState> = _state.asStateFlow()
    }

    private val firmwareUpdateManager = object : FirmwareUpdateManager {
        override fun init(watchInfo: WatchInfo) {
        }

        override fun checkForUpdates(force: Boolean) {
        }

        override val availableUpdates: Flow<FirmwareUpdateCheckState>
            get() = MutableStateFlow(FirmwareUpdateCheckState(false, null))

    }
    private val firmwareUpdater = object : FirmwareUpdater {
        override val firmwareUpdateState: StateFlow<FirmwareUpdater.FirmwareUpdateStatus>
            = MutableStateFlow(FirmwareUpdater.FirmwareUpdateStatus.NotInProgress.Idle())

        override fun init(
            watchPlatform: WatchHardwarePlatform,
            slot: Int?,
            supportsResume: Boolean,
            runningFwVersion: FirmwareVersion,
        ) {
        }

        override fun sideloadFirmware(path: Path) {}

        override fun updateFirmware(update: FirmwareUpdateCheckResult.FoundUpdate) {}

        override fun checkforFirmwareUpdate(force: Boolean) {}
    }
    private val bluetoothStateProvider = object : BluetoothStateProvider {
        override fun init() {
        }

        override val state: StateFlow<BluetoothState> =
            MutableStateFlow(BluetoothState.Enabled).asStateFlow()

        override val scanningAvailable: StateFlow<Boolean> =
            MutableStateFlow(true).asStateFlow()
    }
    private val scanning = object : Scanning {
        override val bluetoothEnabled: StateFlow<BluetoothState>
            get() = TODO("Not yet implemented")
        override val isScanningBle: StateFlow<Boolean>
            get() = TODO("Not yet implemented")
        override val isScanningClassic: StateFlow<Boolean>
            get() = TODO("Not yet implemented")

        override fun startBleScan() {
        }

        override fun stopBleScan() {
        }

        override fun startClassicScan() {
        }

        override fun stopClassicScan() {
        }

        override fun addQemuWatch(address: String, connect: Boolean) {
        }
    }
    private val watchConfig = WatchConfig(multipleConnectedWatchesSupported = false).asFlow()
    private val webServices = object : WebServices {
        override suspend fun fetchLocker(): LockerModelWrapper? {
            TODO("Not yet implemented")
        }

        override suspend fun removeFromLocker(id: Uuid): Boolean {
            TODO("Not yet implemented")
        }

        override suspend fun checkForFirmwareUpdate(watch: WatchInfo, force: Boolean): FirmwareUpdateCheckResult {
            TODO("Not yet implemented")
        }

        override fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo) {
            TODO("Not yet implemented")
        }

        override fun uploadAnalyticsHeartbeat(payload: ByteArray, watchInfo: WatchInfo) {
            TODO("Not yet implemented")
        }
    }
    private val blePlatformConfig =
        BlePlatformConfig(delayBleConnectionsAfterAppStart = false, delayBleDisconnections = false)

    private fun create(scope: CoroutineScope): WatchManager {
        val libPebbleCoroutineScope = LibPebbleCoroutineScope(scope.coroutineContext)
        val connectionCoroutineScope = ConnectionCoroutineScope(scope.coroutineContext)
        val batteryWatcher = BatteryWatcher(connectionCoroutineScope)
        val analyticsLogger = object : ConnectionAnalyticsLogger {
            override fun logEvent(
                name: String,
                props: Map<String, String>?
            ) {
            }
        }
        val langPackInstaller = object : LanguagePackInstaller {
            override val state: StateFlow<LanguagePackInstallState> = MutableStateFlow(
                LanguagePackInstallState.Idle())
        }
        val connectionScopeFactory = object : ConnectionScopeFactory {
            override fun createScope(props: ConnectionScopeProperties): ConnectionScope {
                return TestConnectionScope(
                    identifier = props.identifier,
                    pebbleConnector = connectorFor?.invoke(props.identifier) ?: TestPebbleConnector(),
                    firmwareUpdateManager = firmwareUpdateManager,
                    firmwareUpdater = firmwareUpdater,
                    batteryWatcher = batteryWatcher,
                    analyticsLogger = analyticsLogger,
                    usingBtClassic = false,
                    languagePackInstaller = langPackInstaller,
                )
            }
        }
        val connectionFailureHandler = object : ConnectionFailureHandler {
            override suspend fun handleConnectionFailure(
                identifier: PebbleIdentifier,
                color: WatchColor,
                failure: ConnectionFailureInfo
            ) {
            }
        }
        val analytics = object : LibPebbleAnalytics {
            override fun logEvent(
                name: String,
                parameters: Map<String, String>
            ) {
            }
        }
        val blobDbManager = object : BlobDbDatabaseManager {
            override suspend fun deleteSyncRecordsForStaleDevices() {
            }
        }
        val testSettings = PropertiesSettings(Properties())
        return WatchManager(
            knownWatchDao = knownWatchDao,
            pebbleDeviceFactory = pebbleDeviceFactory,
            createPlatformIdentifier = createPlatformIdentifier,
            connectionScopeFactory = connectionScopeFactory,
            libPebbleCoroutineScope = libPebbleCoroutineScope,
            bluetoothStateProvider = bluetoothStateProvider,
            scanning = HackyProvider { scanning },
            watchConfig = watchConfig,
            clock = Clock.System,
            blePlatformConfig = blePlatformConfig,
            connectionFailureHandler = connectionFailureHandler,
            analytics = analytics,
            blobDbDatabaseManager = blobDbManager,
            settings = testSettings,
            appContext = AppContext(),
            legacyBtClassicMigrator = LegacyBtClassicMigrator(
                knownWatchDao = knownWatchDao,
                settings = testSettings,
                blePlatformConfig = blePlatformConfig,
            ),
            notificationCatchUp = NotificationCatchUp(RealTimeProvider(), NotificationConfig().asFlow()),
        )
    }

//    @Test
//    fun happyCase() = runTest(timeout = 5.seconds) {
//        val watchManager = create(backgroundScope)
//        val scanResult = PebbleScanResult(transport, 0, null)
//        watchManager.init()
//        yield()
//        watchManager.addScanResult(scanResult)
//        watchManager.requestConnection(transport)
//        watchManager.watches.first { it.any { it is ConnectingPebbleDevice } }
//        watchManager.watches.first { it.any { it is ConnectedPebbleDevice } }
//        assertFalse(exceededMax)
//    }

    @Test
    fun repeatConnections() = runTest(timeout = 5.seconds) {
        val watchManager = create(backgroundScope)
        val scanResult = PebbleScanResult(identifier, name, 0, null)
        watchManager.init()
        yield()
        watchManager.addScanResult(scanResult)
        watchManager.requestConnection(identifier)
        for (i in 1..20) {
            watchManager.watches.first { totalConnections >= i && it.any { it is ConnectingPebbleDevice } }
        }
        assertFalse(exceededMax)
    }

    private fun seededWatch(lastConnected: MillisecondInstant?) = KnownWatchItem(
        transportIdentifier = "addr",
        transportType = TransportType.BluetoothLe,
        name = name,
        // BondedWatchSeeder writes these placeholder values for OS-bonded watches.
        runningFwVersion = UNKNOWN_WATCH_SERIAL_OR_VERSION,
        serial = UNKNOWN_WATCH_SERIAL_OR_VERSION,
        connectGoal = true,
        lastConnected = lastConnected,
    )

    /**
     * A BondedWatchSeeder stub (never connected by this install, so `lastConnected == null`) must
     * be treated as a FIRST connection — otherwise BlobDB.init() skips wiping the watch's blob DBs
     * after a reinstall and stale weather/etc. records linger. Regression test for that.
     */
    @Test
    fun seededWatchIsNotTreatedAsPreviouslyConnected() = runTest(timeout = 5.seconds) {
        seededKnownWatches = listOf(seededWatch(lastConnected = null))
        val watchManager = create(backgroundScope)
        watchManager.init()
        watchManager.watches.first { totalConnections >= 1 && it.any { it is ConnectingPebbleDevice } }
        assertEquals(false, lastPreviouslyConnected)
    }

    @Test
    fun holdKeepsTheGoalWatchDisconnectedUntilReleased() = runTest(timeout = 5.seconds) {
        seededKnownWatches = listOf(seededWatch(lastConnected = MillisecondInstant(Instant.fromEpochMilliseconds(1_000))))
        val watchManager = create(backgroundScope)
        // Taken before init, like a daemon that starts inside its Quiet Time window.
        val hold = watchManager.holdDisconnected("test")
        watchManager.init()
        delay(5.seconds)
        assertEquals(0, totalConnections)
        hold.close()
        hold.close() // harmless
        watchManager.watches.first { totalConnections >= 1 && it.any { it is ConnectingPebbleDevice } }
    }

    @Test
    fun holdEndsAnAttemptAndStopsTheRetries() = runTest(timeout = 5.seconds) {
        seededKnownWatches = listOf(seededWatch(lastConnected = MillisecondInstant(Instant.fromEpochMilliseconds(1_000))))
        val watchManager = create(backgroundScope)
        watchManager.init()
        watchManager.watches.first { totalConnections >= 2 }
        val hold = watchManager.holdDisconnected("test")
        delay(1.seconds)
        val held = totalConnections
        delay(5.seconds)
        assertEquals(held, totalConnections)
        hold.close()
        watchManager.watches.first { totalConnections > held }
    }

    @Test
    fun watchWithPriorConnectionIsTreatedAsPreviouslyConnected() = runTest(timeout = 5.seconds) {
        seededKnownWatches = listOf(seededWatch(lastConnected = MillisecondInstant(Instant.fromEpochMilliseconds(1_000))))
        val watchManager = create(backgroundScope)
        watchManager.init()
        watchManager.watches.first { totalConnections >= 1 && it.any { it is ConnectingPebbleDevice } }
        assertEquals(true, lastPreviouslyConnected)
    }

    /**
     * A connector whose connect() never finishes: an away BLE watch whose standing Device1.Connect() keeps
     * arming. [endsOnDisconnect] is the TransportConnector contract (BluezGattConnector after the fork fix):
     * disconnect() completes `disconnected` even though no link ever came up. Without it the attempt only
     * ends with the connection scope, which WatchManager closes only after `disconnected` completes.
     */
    inner class ArmingConnector(
        private val id: PebbleIdentifier,
        private val endsOnDisconnect: Boolean,
        private val connectCalled: CompletableDeferred<Unit>,
        private val stop: CompletableDeferred<Unit>,
    ) : PebbleConnector {
        private val _disconnected = CompletableDeferred<ConnectionFailureReason>()
        private val _state = MutableStateFlow<ConnectingPebbleState>(ConnectingPebbleState.Inactive(id))

        override suspend fun connect(knownWatchProperties: KnownWatchProperties?, lastError: ConnectionFailureReason?) {
            _state.value = ConnectingPebbleState.Connecting(id)
            connectCalled.complete(Unit)
            // Arming until the attempt is ended (or the test is over).
            select<Unit> {
                _disconnected.onAwait { }
                stop.onAwait { }
            }
        }

        override fun disconnect() {
            if (endsOnDisconnect) _disconnected.complete(ConnectionFailureReason.NotAnError_DisconnectRequested)
        }

        override val disconnected: WasDisconnected = WasDisconnected(_disconnected)
        override val state: StateFlow<ConnectingPebbleState> = _state.asStateFlow()
    }

    /** Watch A holds the goal and arms forever (out of range); then the goal moves to B. Returns whether B's
     *  connect() was called within a few seconds (the single-watch slot must have been freed by A). */
    private suspend fun goalMovesOffAnArmingWatch(scope: CoroutineScope, endsOnDisconnect: Boolean): Boolean {
        val a = identifier
        val b = "addr2".asPebbleBleIdentifier()
        val aConnecting = CompletableDeferred<Unit>()
        val bConnecting = CompletableDeferred<Unit>()
        val stop = CompletableDeferred<Unit>()
        connectorFor = { id ->
            if (id == a) ArmingConnector(id, endsOnDisconnect, aConnecting, stop)
            else ArmingConnector(id, endsOnDisconnect = true, bConnecting, stop)
        }
        seededKnownWatches = listOf(seededWatch(lastConnected = MillisecondInstant(Instant.fromEpochMilliseconds(1_000))))
        val watchManager = create(scope)
        watchManager.init()
        // The connectors run on their own (real) dispatcher: wait in real time.
        withContext(Dispatchers.Default) { withTimeoutOrNull(3.seconds) { aConnecting.await() } }
            ?: error("A never started connecting")
        watchManager.addScanResult(PebbleScanResult(b, "other", 0, null))
        watchManager.requestConnection(b)
        val bConnected = withContext(Dispatchers.Default) { withTimeoutOrNull(3.seconds) { bConnecting.await() } }
        stop.complete(Unit)
        return bConnected != null
    }

    @Test
    fun goalMovedOffAnArmingWatchConnectsTheOther() = runTest(timeout = 10.seconds) {
        assertTrue(goalMovesOffAnArmingWatch(backgroundScope, endsOnDisconnect = true))
    }

    /** Documents why the contract matters: a connector that ignores disconnect() while arming (the BlueZ
     *  connector before the fork fix) keeps the slot, and the other watch never gets a connect(). */
    @Test
    fun anArmingWatchThatIgnoresDisconnectKeepsTheSlot() = runTest(timeout = 10.seconds) {
        assertFalse(goalMovesOffAnArmingWatch(backgroundScope, endsOnDisconnect = false))
    }
}
