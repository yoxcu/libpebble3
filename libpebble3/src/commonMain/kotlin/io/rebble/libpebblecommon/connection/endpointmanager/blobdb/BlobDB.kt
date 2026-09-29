package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import co.touchlab.kermit.Logger
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.Settings
import com.russhwolf.settings.serialization.decodeValue
import com.russhwolf.settings.serialization.encodeValue
import coredev.BlobDatabase
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.WatchLinkActivity
import io.rebble.libpebblecommon.database.dao.BlobDbDao
import io.rebble.libpebblecommon.database.dao.BlobDbRecord
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.dao.NotificationRuleDao
import io.rebble.libpebblecommon.database.dao.TimelineNotificationRealDao
import io.rebble.libpebblecommon.database.dao.TimelinePinRealDao
import io.rebble.libpebblecommon.database.dao.TimelineReminderRealDao
import io.rebble.libpebblecommon.database.dao.ValueParams
import io.rebble.libpebblecommon.database.dao.VibePatternDao
import io.rebble.libpebblecommon.database.dao.WatchPrefRealDao
import io.rebble.libpebblecommon.database.dao.WeatherAppRealDao
import io.rebble.libpebblecommon.database.entity.AppPrefsEntryDao
import io.rebble.libpebblecommon.database.dao.HealthSettingsEntryRealDao
import io.rebble.libpebblecommon.database.entity.HealthStatDao
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.PlatformConfig
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.BlobDB2Command
import io.rebble.libpebblecommon.packets.blobdb.BlobDB2Response
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.services.blobdb.DbWrite
import io.rebble.libpebblecommon.services.blobdb.WriteType
import io.rebble.libpebblecommon.web.withTimeoutOr
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class BlobDbDaos(
    private val lockerEntryDao: LockerEntryRealDao,
    private val notificationsDao: TimelineNotificationRealDao,
    private val timelinePinDao: TimelinePinRealDao,
    private val timelineReminderDao: TimelineReminderRealDao,
    private val notificationAppRealDao: NotificationAppRealDao,
    private val healthSettingsDao: HealthSettingsEntryRealDao,
    private val healthStatDao: HealthStatDao,
    private val vibePatternDao: VibePatternDao,
    private val platformConfig: PlatformConfig,
    private val watchPrefDao: WatchPrefRealDao,
    private val weatherAppDao: WeatherAppRealDao,
    private val appPrefsEntryDao: AppPrefsEntryDao,
    private val notificationRuleDao: NotificationRuleDao,
) {
    fun get(): Set<BlobDbDao<BlobDbRecord>> = buildSet {
        add(lockerEntryDao)
        add(notificationsDao)
        add(timelinePinDao)
        add(timelineReminderDao)
        add(healthSettingsDao)
        add(healthStatDao)
        if (platformConfig.syncNotificationApps) {
            add(notificationAppRealDao)
        }
        add(watchPrefDao)
        add(weatherAppDao)
        add(appPrefsEntryDao)
        // because typing
    } as Set<BlobDbDao<BlobDbRecord>>
    
    fun getVibePatternDao(): VibePatternDao = vibePatternDao
    fun getNotificationRuleDao(): io.rebble.libpebblecommon.database.dao.NotificationRuleDao = notificationRuleDao
}

interface TimeProvider {
    fun now(): Instant
}

class RealTimeProvider : TimeProvider {
    override fun now(): Instant = Clock.System.now()
}

@Serializable
data class DevicesWhichHaveSyncedSettings(val identifiers: Set<String>)

@OptIn(ExperimentalSettingsApi::class)
class BlobDB(
    private val watchScope: ConnectionCoroutineScope,
    private val blobDBService: BlobDBService,
    private val identifier: PebbleIdentifier,
    private val blobDatabases: BlobDbDaos,
    private val timeProvider: TimeProvider,
    private val notificationConfigFlow: NotificationConfigFlow,
    private val settings: Settings,
    private val libPebbleConfigFlow: LibPebbleConfigFlow,
    private val linkActivity: WatchLinkActivity,
    private val notificationCatchUp: NotificationCatchUp,
) {
    protected val watchIdentifier: String = identifier.asString

    companion object {
        private val BLOBDB_RESPONSE_TIMEOUT = 10.seconds
        private val QUERY_REFRESH_PERIOD = 1.hours
        private val PREF_KEY_DEVICES_HAVE_SYNCED_SETTINGS = "devicesHaveSyncedSettings"
        private const val NUL_CHAR: Char = '\u0000'
    }

    private val logger = Logger.withTag("BlobDB-$watchIdentifier")
    private val random = Random
    // To prevent overlapping insert/delete operations
    private val operationLock = Mutex()
    private val databasesWhichWillSync = MutableStateFlow(setOf(BlobDatabase.WatchPrefs))

    // Fork (stoandl): dirty records not yet attempted on this connection, per dirty query
    // ("<db>/insert" or "<db>/delete"), summed into WatchLinkActivity. A host that suspends right after
    // a push wake uses it to hold its sleep delay lock until a fresh notification has gone out.
    private val unattempted = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Per-query bookkeeping: hashes of the latest dirty list, and those already handed to the watch. */
    private data class QueryProgress(val latest: Set<Int> = emptySet(), val attempted: Set<Int> = emptySet()) {
        val pending: Int get() = latest.count { it !in attempted }
    }

    /**
     * Run [query] continually, updating the query timestamp (so that it does not become stale).
     */
    private fun dynamicQuery(
        dao: BlobDbDao<BlobDbRecord>,
        insert: Boolean,
        // Fork (stoandl): the `onlyInsertAfter` threshold; null = upstream (records created from now on).
        insertOnlyAfter: Instant? = null,
        collector: suspend (items: List<BlobDbRecord>) -> Unit,
    ) {
        val initialTimestamp = insertOnlyAfter ?: timeProvider.now()
        val queryKey = "${dao.databaseId()}/${if (insert) "insert" else "delete"}"
        // A record the watch rejects (or that has no value for this watch) stays dirty in the DB; once
        // it has been attempted on this connection it must not count as pending, or the host would
        // wait out its whole sleep delay on every suspend.
        val progress = MutableStateFlow(QueryProgress())
        fun publish() {
            // Loop until [progress] did not change under us, so the onEach producer and the collector
            // can't race each other into leaving a stale count behind.
            while (true) {
                val snapshot = progress.value
                unattempted.update { it + (queryKey to snapshot.pending) }
                if (progress.value == snapshot) break
            }
        }
        watchScope.launch {
            val tickerFlow = flow {
                while (true) {
                    emit(Unit)
                    delay(QUERY_REFRESH_PERIOD)
                }
            }
            tickerFlow
                .flatMapLatest {
                    logger.d { "dynamicQuery: refreshing (${dao.databaseId()}" }
                    if (insert) {
                        dao.dirtyRecordsForWatchInsert(
                            transport = identifier.asString,
                            timestampMs = timeProvider.now().toEpochMilliseconds(),
                            insertOnlyAfterMs = initialTimestamp.toEpochMilliseconds(),
                        )
                    } else {
                        dao.dirtyRecordsForWatchDelete(
                            transport = identifier.asString,
                            timestampMs = timeProvider.now().toEpochMilliseconds(),
                        )
                    }
                }
                // Upstream of conflate(): runs as soon as Room emits, even while the collector below
                // is busy or debouncing — so a record inserted mid-sync counts as pending at once.
                .onEach { items ->
                    val latest = items.mapTo(HashSet()) { it.recordHashcode }
                    progress.update { QueryProgress(latest = latest, attempted = it.attempted intersect latest) }
                    publish()
                }
                .conflate()
                .distinctUntilChanged()
                .collect { items ->
                    try {
                        collector(items)
                    } finally {
                        progress.update { it.copy(attempted = it.attempted + items.map { i -> i.recordHashcode }) }
                        publish()
                    }
                    // debounce
                    delay(1.seconds)
                }
        }
    }

    fun init(
        watchType: WatchType,
        unfaithful: Boolean,
        previouslyConnected: Boolean,
        capabilities: Set<ProtocolCapsFlag>,
        firmwareVersion: FirmwareVersion,
    ) {
        val params = ValueParams(
            platform = watchType,
            capabilities = capabilities,
            firmwareVersion = firmwareVersion,
            vibePatternDao = blobDatabases.getVibePatternDao(),
            notificationRuleDao = blobDatabases.getNotificationRuleDao(),
            libPebbleConfigFlow = libPebbleConfigFlow,
        )
        val deviceHasPreviouslySyncedSettings =
            loadDevicePreviousSettingsSyncState().identifiers.contains(identifier.asString)
        // Fork (stoandl): a connection that wipes the watch (below) bounds its notification catch-up.
        // Recorded here, not when the sync starts: the connection turns Connected as soon as init()
        // returns, which persists the watch as known — a drop during the handshake below would otherwise
        // leave the next (no longer fresh) connection with wiped sync state and no floor, catching up on
        // notifications from before the pairing.
        if (unfaithful || !previouslyConnected) {
            notificationCatchUp.markFreshStart(identifier, timeProvider.now())
        }
        // Fork (stoandl): publish the unattempted-record count (see [unattempted]); the finally clears
        // it when the connection scope ends.
        watchScope.launch {
            val source = "blobdb:$watchIdentifier"
            try {
                unattempted.collect { linkActivity.report(source, it.values.sum()) }
            } finally {
                linkActivity.report(source, 0)
            }
        }
        watchScope.launch {
            // Watch isn't sending a version response
            val blobDbVersion = if (capabilities.contains(ProtocolCapsFlag.SupportsBlobDbVersion)) {
                val response = blobDBService.send(BlobDB2Command.Version(generateToken()))
                logger.v { "got version response: $response" }
                if (response is BlobDB2Response.VersionResponseCmd) {
                    response.version.get().toInt()
                } else {
                    0
                }
            } else {
                0
            }
            logger.d { "BlobDb version: $blobDbVersion" }

            if (unfaithful || !previouslyConnected) {
                // Mark all of our local DBs not synched (before handling any writes from the watch)
                blobDatabases.get().forEach { db ->
                    db.markAllDeletedFromWatch(identifier.asString)
                }
            }

            if (unfaithful || !deviceHasPreviouslySyncedSettings) {
                if (blobDbVersion >= 1) {
                    // Request a full sync of watch prefs
                    val dirtyResponse = blobDBService.send(
                        BlobDB2Command.MarkAllDirty(
                            generateToken(),
                            BlobDatabase.WatchPrefs,
                        )
                    )
                    logger.v { "Marked all dirty for watch prefs: $dirtyResponse" }
                }
            }

            watchScope.launch {
                blobDBService.writes.collect { message ->
                    logger.v { "write: $message" }
                    if (message.database == BlobDatabase.WatchPrefs && !deviceHasPreviouslySyncedSettings) {
                        markDeviceHasSyncedSettings()
                    }
                    val effectiveDatabase = effectiveDatabaseFor(message)
                    val dao = blobDatabases.get().find { it.databaseId() == effectiveDatabase }
                    val result = dao?.handleWrite(
                        write = message,
                        transport = identifier.asString,
                        params,
                    ) ?: run {
                        logger.v { "unhandled write: $message (key=${message.key.joinToString()} value=${message.value.joinToString()}" }
                        BlobResponse.BlobStatus.Success
                    }
                    val response = when (message.writeType) {
                        // TODO only one of these during initial sync?
                        WriteType.Write -> BlobDB2Response.WriteResponse(message.token, result)
                        WriteType.WriteBack -> BlobDB2Response.WriteBackResponse(
                            message.token,
                            result
                        )
                    }
                    blobDBService.sendResponse(response)
                }
            }

            if (blobDbVersion >= 1) {
                watchScope.launch {
                    blobDBService.syncCompletes.collect {
                        databasesWhichWillSync.value -= it
                    }
                }

                withTimeoutOr(timeout = 30.seconds, block = {
                    databasesWhichWillSync.first { it.isEmpty() }
                }, onTimeout = {
                    logger.w { "Timed out waiting for sync to complete" }
                })
            }

            if (unfaithful || !previouslyConnected) {
                logger.d("unfaithful: wiping DBs on watch")
                // Clear all DBs on watch (whether we have local DBs for them or not)
                BlobDatabase.entries.forEach { db ->
                    if (db.sendClear) {
                        sendWithTimeout(
                            BlobCommand.ClearCommand(
                                token = generateToken(),
                                database = db,
                            )
                        )
                    }
                }
            }

            // Fork (stoandl): the notification DB may also send what this watch missed while it was away
            // (NotificationCatchUp; upstream when the window is 0). Every other DB keeps upstream's threshold.
            val syncStart = timeProvider.now()
            val notificationsAfter = notificationCatchUp.insertOnlyAfter(watch = identifier, now = syncStart)
            logger.d { "Notification catch-up: threshold $notificationsAfter (sync start $syncStart)" }
            blobDatabases.get().forEach { db ->
                db.deleteStaleRecords(timeProvider.now().toEpochMilliseconds())
                val isNotificationDb = db.databaseId() == BlobDatabase.Notification
                // Fork (stoandl): the notification DB's first dirty list is what this connection catches
                // up on; log it only when there is something (the threshold alone proves nothing).
                var catchUpBatch = isNotificationDb && notificationsAfter < syncStart
                dynamicQuery(
                    dao = db,
                    insert = true,
                    insertOnlyAfter = notificationsAfter.takeIf { isNotificationDb },
                ) { dirty ->
                    if (catchUpBatch && dirty.isNotEmpty()) {
                        logger.i { "Notification catch-up: sending ${dirty.size} unsent notification(s) created after $notificationsAfter" }
                    }
                    catchUpBatch = false
                    dirty.forEach { item ->
                        operationLock.withLock {
                            handleInsert(db, item, params, blobDbVersion)
                        }
                    }
                }
                dynamicQuery(dao = db, insert = false) { dirty ->
                    dirty.forEach { item ->
                        operationLock.withLock {
                            handleDelete(db, item)
                        }
                    }
                }
            }
        }
    }

    // The watch firmware sends health settings (activityPreferences, unitsDistance,
    // hrmPreferences, heartRatePreferences) via the WatchPrefs BlobDB, but the phone stores
    // them in HealthParams. Route those keys to the health settings DAO.
    private fun effectiveDatabaseFor(message: DbWrite): BlobDatabase {
        if (message.database != BlobDatabase.WatchPrefs) return message.database
        val key = message.key.toByteArray().decodeToString().trimEnd(NUL_CHAR)
        return when (key) {
            "activityPreferences",
            "unitsDistance",
            "hrmPreferences",
            "heartRatePreferences" -> BlobDatabase.HealthParams
            else -> message.database
        }
    }

    private fun loadDevicePreviousSettingsSyncState() =
        settings.decodeValue(PREF_KEY_DEVICES_HAVE_SYNCED_SETTINGS, DevicesWhichHaveSyncedSettings(emptySet()))

    private fun markDeviceHasSyncedSettings() {
        logger.d { "markDeviceHasSyncedSettings: ${identifier.asString}" }
        val devices = loadDevicePreviousSettingsSyncState()
        settings.encodeValue(
            PREF_KEY_DEVICES_HAVE_SYNCED_SETTINGS,
            devices.copy(identifiers = devices.identifiers + identifier.asString),
        )
    }

    private suspend fun handleInsert(
        db: BlobDbDao<BlobDbRecord>,
        item: BlobDbRecord,
        params: ValueParams,
        blobDbVersion: Int,
    ) {
        val value = item.record.value(params)
        val key = item.record.key()
        val keyString = key.keyAsString(db.databaseId())
        if (value == null) {
            logger.d { "handleInsert: value is null: ${db.databaseId()} $keyString" }
            return
        }
        if (notificationConfigFlow.value.obfuscateContent) {
            logger.d("insert: ${db.databaseId()} $key ($keyString) hashcode: ${item.recordHashcode}")
        } else {
            logger.d("insert: ${db.databaseId()} $keyString - $item")
        }
        val timestamp = item.record.timestamp()
        val command = when {
            timestamp != null && blobDbVersion >= 1 -> {
                logger.v { "inserting with timestamp: $timestamp" }
                BlobCommand.InsertWithTimestampCommand(
                    token = generateToken(),
                    database = db.databaseId(),
                    key = key,
                    value = value,
                    timestamp = timestamp,
                )
            }
            timestamp == null || db.databaseId().supportsLegacyInsert -> BlobCommand.InsertCommand(
                token = generateToken(),
                database = db.databaseId(),
                key = key,
                value = value,
            )
            else -> null
        }
        if (command == null) {
            logger.i { "Not processing insert for ${db.databaseId()}: $keyString because not supported by watch" }
            return
        }
        val bytes = command.serialize().asByteArray()
        val result = sendWithTimeout(command)
        logger.d("insert: result = ${result?.responseValue}")
        when (result?.responseValue) {
            BlobResponse.BlobStatus.Success,
            // Stale = mark as synced (watch will never accept this record)
            BlobResponse.BlobStatus.DataStale-> db.markSyncedToWatch(
                transport = identifier.asString,
                item = item,
                hashcode = item.recordHashcode,
            )

            else -> Unit
        }
    }

    private suspend fun handleDelete(
        db: BlobDbDao<BlobDbRecord>,
        item: BlobDbRecord,
    ) {
        val key = item.record.key()
        val keyString = key.keyAsString(db.databaseId())
        if (notificationConfigFlow.value.obfuscateContent) {
            logger.d("delete: ${db.databaseId()} $key ($keyString) hashcode: ${item.recordHashcode}")
        } else {
            logger.d("delete: ${db.databaseId()} $keyString - $item")
        }
        val result = sendWithTimeout(
            BlobCommand.DeleteCommand(
                token = generateToken(),
                database = db.databaseId(),
                key = key,
            )
        )
        logger.d("delete: result = ${result?.responseValue}")
        if (result?.responseValue == BlobResponse.BlobStatus.Success) {
            db.markDeletedFromWatch(
                transport = identifier.asString,
                item = item,
                hashcode = item.recordHashcode,
            )
        }
    }

    private fun generateToken(): UShort {
        return random.nextInt(0, UShort.MAX_VALUE.toInt()).toUShort()
    }

    private suspend fun sendWithTimeout(command: BlobCommand): BlobResponse? =
        withTimeoutOrNull(BLOBDB_RESPONSE_TIMEOUT) {
            blobDBService.send(command)
        }
}

private fun UByteArray.keyAsString(db: BlobDatabase): String = when {
    db.keyIsUuid() -> try {
        Uuid.fromUByteArray(this).toString()
    } catch (_: Exception) {
        ""
    }
    else -> ""
}

private fun BlobDatabase.keyIsUuid(): Boolean = when (this) {
    BlobDatabase.Pin, BlobDatabase.App, BlobDatabase.Reminder, BlobDatabase.Notification -> true
    else -> false
}
