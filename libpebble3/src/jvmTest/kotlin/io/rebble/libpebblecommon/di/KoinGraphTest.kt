package io.rebble.libpebblecommon.di

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import io.rebble.libpebblecommon.BleConfig
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.calls.SystemCallLog
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.CreatePlatformIdentifier
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PlatformFlags
import io.rebble.libpebblecommon.connection.TokenProvider
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.connection.asPebbleBleIdentifier
import io.rebble.libpebblecommon.connection.asPebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.database.DATABASE_FILENAME
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.getRoomDatabase
import io.rebble.libpebblecommon.js.InjectedPKJSHttpInterceptors
import io.rebble.libpebblecommon.js.JsRunner
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.music.SystemMusicControl
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.stoandlConfigDir
import io.rebble.libpebblecommon.time.TimeChanged
import io.rebble.libpebblecommon.util.SystemGeolocation
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Resolves the whole JVM Koin graph the way the stoandl daemon builds it: [initKoin] with the JVM
 * platform + PKJS modules, then an override module standing in for the bindings the daemon supplies
 * itself (PebbleIntegration.kt). A binding commonMain needs but nothing provides on JVM is a
 * NoDefinitionFoundException at daemon startup, not a compile error — upstream's own JVM module is
 * a TODO(), so a bump can add one silently. This test is where that shows up instead.
 *
 * The graph test swaps the Database binding for an in-memory one, so [existingV38DatabaseMigrates]
 * covers that binding's production chain separately.
 */
class KoinGraphTest {
    // The daemon's defaults: forward (non-reversed) PPoG, LAN developer connection.
    private val config = LibPebbleConfig(
        bleConfig = BleConfig(legacyReversedPPoG = false, useReversedPpogV2 = false),
        watchConfig = WatchConfig(lanDevConnection = true),
    )

    /**
     * Test doubles for exactly the bindings the daemon overrides unconditionally, so the graph
     * matches production. The fork deliberately has no JVM no-op for most of these (fail fast), so
     * without this module the test would fail on them. PlatformConfig is overridden only when
     * notification.sync_to_watch is on (default off), so the JVM module's binding stays here.
     */
    private val daemonOverrides = module {
        single<NotificationListenerConnection> { stub<NotificationListenerConnection>() }
        single { BleConfigFlow(MutableStateFlow(config)) }
        single { WatchConfigFlow(MutableStateFlow(config)) }
        // PebbleBle now reads LibPebbleConfigFlow rather than BleConfigFlow, so the daemon has to
        // pin this one as well for its transport choice to survive persisted preferences.
        single { LibPebbleConfigFlow(MutableStateFlow(config)) }
        single<PlatformNotificationActionHandler> { stub<PlatformNotificationActionHandler>() }
        single<TimeChanged> { stub<TimeChanged>() }
        single { PlatformFlags(PhoneAppVersion.PlatformFlag.makeFlags(PhoneAppVersion.OSType.Android, emptyList())) }
        single<SystemCallLog> { stub<SystemCallLog>() }
        single<SystemMusicControl> { stub<SystemMusicControl>() }
        single<SystemCalendar> { stub<SystemCalendar>() }
        single<SystemGeolocation> { stub<SystemGeolocation>() }
    }

    /** Test-only: keep the run off the user's real Java preferences and ~/.config/stoandl DB. */
    private val hermeticOverrides = module {
        single<Settings> { PropertiesSettings(Properties()) }
        single<Database> {
            Room.inMemoryDatabaseBuilder<Database>()
                .setDriver(BundledSQLiteDriver())
                .build()
        }
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun jvmGraphResolves() {
        val koin = initKoin(
            defaultConfig = config,
            webServices = stub<WebServices>(),
            appContext = AppContext(),
            tokenProvider = stub<TokenProvider>(),
            proxyTokenProvider = MutableStateFlow(null),
            transcriptionProvider = stub<TranscriptionProvider>(),
            injectedPKJSHttpInterceptors = InjectedPKJSHttpInterceptors(emptyList()),
        )
        koin.loadModules(listOf(daemonOverrides, hermeticOverrides), allowOverride = true)

        // What the daemon does first.
        koin.get<LibPebble>()
        // Nothing fails to resolve without it (the flag defaults to false); PebbleBle then falls back
        // to upstream's post-discovery order and the watch's first RESET_REQUEST is dropped.
        assertTrue(
            koin.get<BlePlatformConfig>().registerForwardPpogBeforeConnect,
            "JVM must register forward PPoG before connecting (BlueZ)",
        )

        // Every root definition. Per-connection ones are resolved through real connection scopes
        // below. The PKJS JsRunner factory needs per-app parametersOf(...); its injected deps are
        // root singles, so they're covered here.
        val failures = mutableListOf<String>()
        // Koin has no public API that lists definitions (Module.mappings is @KoinInternalApi too),
        // so instanceRegistry is the one internal call; the rest is public. ConnectionScope is the
        // only scope archetype, so everything outside it must resolve from the root. A new archetype
        // then fails here, naming its scope, instead of going unchecked.
        koin.instanceRegistry.instances.values
            .map { it.beanDefinition }
            .distinct()
            .filterNot { it.scopeQualifier == named<ConnectionScope>() }
            .filterNot { JsRunner::class.java.isAssignableFrom(it.primaryType.java) }
            .forEach { definition ->
                try {
                    koin.get<Any>(definition.primaryType, definition.qualifier)
                } catch (e: Exception) {
                    failures += "${definition.primaryType.qualifiedName} (scope ${definition.scopeQualifier.value}): $e"
                }
            }
        assertTrue(failures.isEmpty(), "Unresolvable Koin definitions:\n" + failures.joinToString("\n"))

        // One connection scope per JVM transport, created the way WatchManager does it. Creating it
        // resolves the whole per-connection graph (connector, services, endpoint managers).
        val scopeFactory = koin.get<ConnectionScopeFactory>()
        val createPlatformIdentifier = koin.get<CreatePlatformIdentifier>()
        listOf(
            """{"object_path":"/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF"}""".asPebbleBleIdentifier(),
            "AA:BB:CC:DD:EE:01".asPebbleBtClassicIdentifier(),
        ).forEach { identifier ->
            val platformIdentifier = assertNotNull(
                createPlatformIdentifier.identifier(identifier, "Pebble", lastAttemptFailed = false),
                "no platform identifier for $identifier",
            )
            val connectionScope = scopeFactory.createScope(
                ConnectionScopeProperties(
                    identifier = identifier,
                    scope = ConnectionCoroutineScope(SupervisorJob() + Dispatchers.Default),
                    platformIdentifier = platformIdentifier,
                    color = WatchColor.Unknown,
                )
            )
            connectionScope.close()
        }
    }

    /**
     * An existing stoandl libpebble3.db, opened through the same [getRoomDatabase] chain as the
     * production Database binding, must migrate up rather than throw or be wiped (a wipe loses known
     * watches, per-app mutes and the locker). The fork never changed an entity, so a stoandl
     * database is exactly the base's schema 38 export.
     */
    @Test
    fun existingV38DatabaseMigrates() {
        // stoandlConfigDir() prefers XDG_CONFIG_HOME, which a running JVM can't override: skip
        // rather than risk opening the user's real database.
        assumeTrue("XDG_CONFIG_HOME is set", System.getenv("XDG_CONFIG_HOME").isNullOrBlank())
        // Gradle runs tests from the project dir, where Room exports its schemas.
        val schemaDir = File("schema/io.rebble.libpebblecommon.database.Database")
        assertTrue(schemaDir.isDirectory, "no Room schema export at ${schemaDir.absolutePath}")
        val latestVersion = schemaDir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()

        val home = Files.createTempDirectory("stoandl-db-migration").toFile()
        val realHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        try {
            val dbFile = File(stoandlConfigDir().apply { mkdirs() }, DATABASE_FILENAME)
            val driver = BundledSQLiteDriver()
            driver.open(dbFile.absolutePath).use { connection ->
                connection.createRoomSchema(File(schemaDir, "38.json"))
                connection.execSQL(
                    "INSERT INTO KnownWatchItem (transportIdentifier, transportType, name, " +
                        "runningFwVersion, serial, connectGoal) " +
                        "VALUES ('AA:BB:CC:DD:EE:01', 'BluetoothClassic', 'Pebble Time', 'v4.4.0', 'Q0000', 1)"
                )
                connection.execSQL(
                    "INSERT INTO NotificationAppItemEntity (recordHashcode, deleted, packageName, " +
                        "name, muteState, channelGroups, stateUpdated, lastNotified) " +
                        "VALUES (0, 0, 'org.example.chat', 'Chat', 'Always', '[]', 0, 0)"
                )
                connection.execSQL(
                    "INSERT INTO LockerEntryEntity (recordHashcode, deleted, id, version, title, " +
                        "type, developerName, configurable, pbwVersionCode, sideloaded, platforms) " +
                        "VALUES (0, 0, '00000000-0000-0000-0000-000000000001', '1.0', 'App', " +
                        "'watchapp', 'Dev', 0, '1', 1, '[]')"
                )
                connection.execSQL(
                    "INSERT INTO LockerEntrySyncEntity (recordId, transport, watchSynchHashcode) " +
                        "VALUES ('00000000-0000-0000-0000-000000000001', 'AA:BB:CC:DD:EE:01', 5)"
                )
            }

            val database = getRoomDatabase(AppContext())
            try {
                // The first query opens the file: every migration runs and Room validates the result.
                val watches = runBlocking { database.knownWatchDao().knownWatches() }
                assertEquals(listOf("AA:BB:CC:DD:EE:01"), watches.map { it.transportIdentifier })
            } finally {
                database.close()
            }

            driver.open(dbFile.absolutePath).use { connection ->
                assertEquals(latestVersion.toLong(), connection.queryLong("PRAGMA user_version"))
                assertEquals(
                    "Always",
                    connection.queryText("SELECT muteState FROM NotificationAppItemEntity WHERE packageName = 'org.example.chat'"),
                )
                // MIGRATION_39_40 bumps every sync hash, so the locker re-syncs once after the upgrade.
                assertEquals(6L, connection.queryLong("SELECT watchSynchHashcode FROM LockerEntrySyncEntity"))
            }
        } finally {
            System.setProperty("user.home", realHome)
            home.deleteRecursively()
        }
    }
}

/** Recreates a Room schema export as the database file an app at that version leaves behind. */
private fun SQLiteConnection.createRoomSchema(schemaExport: File) {
    val database = Json.parseToJsonElement(schemaExport.readText()).jsonObject.getValue("database").jsonObject
    for (entity in database.getValue("entities").jsonArray.map { it.jsonObject }) {
        val table = entity.getValue("tableName").jsonPrimitive.content
        val indices = entity["indices"]?.jsonArray?.map { it.jsonObject.getValue("createSql") }.orEmpty()
        (listOf(entity.getValue("createSql")) + indices).forEach {
            execSQL(it.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
        }
    }
    database.getValue("setupQueries").jsonArray.forEach { execSQL(it.jsonPrimitive.content) }
    execSQL("PRAGMA user_version = ${database.getValue("version").jsonPrimitive.int}")
}

private fun SQLiteConnection.queryLong(sql: String): Long = prepare(sql).use { statement ->
    check(statement.step()) { "no row for: $sql" }
    statement.getLong(0)
}

private fun SQLiteConnection.queryText(sql: String): String = prepare(sql).use { statement ->
    check(statement.step()) { "no row for: $sql" }
    statement.getText(0)
}

/**
 * Interface double for a binding the daemon (or initKoin's caller) supplies. Building the graph
 * must not call into it, so anything but Object's own methods fails loudly with the member's name.
 */
private inline fun <reified T : Any> stub(): T {
    val name = T::class.java.simpleName
    return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "toString" -> "stub $name"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> throw UnsupportedOperationException("$name.${method.name} called while building the graph")
        }
    } as T
}
