package io.rebble.libpebblecommon.di

import androidx.compose.ui.graphics.ImageBitmap
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.calls.LegacyPhoneReceiver
import io.rebble.libpebblecommon.calendar.PlatformCalendarActionHandler
import io.rebble.libpebblecommon.connection.OtherPebbleApp
import io.rebble.libpebblecommon.connection.OtherPebbleApps
import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.connection.PlatformFlags
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.classic.pebble.BtClassicConnector
import io.rebble.libpebblecommon.connection.bt.classic.transport.BluezBtClassicConnector
import io.rebble.libpebblecommon.connection.bt.classic.transport.BluezClassicScanner
import io.rebble.libpebblecommon.connection.bt.classic.transport.ClassicScanner
import org.koin.dsl.bind
import io.rebble.libpebblecommon.contacts.SystemContact
import io.rebble.libpebblecommon.contacts.SystemContacts
import io.rebble.libpebblecommon.database.entity.BaseAction
import io.rebble.libpebblecommon.database.entity.TimelinePin
import io.rebble.libpebblecommon.imaging.NoNotificationImages
import io.rebble.libpebblecommon.imaging.NotificationImageProvider
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.plugin.PhoneBatteryMonitor
import io.rebble.libpebblecommon.plugin.PhoneNetworkMonitor
import io.rebble.libpebblecommon.plugin.PlatformPlugins
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single {
        PhoneCapabilities(
            CommonPhoneCapabilities + setOf(
                ProtocolCapsFlag.SupportsExtendedMusicProtocol,
            )
        )
    }

    single {
        PlatformFlags(
            PhoneAppVersion.PlatformFlag.makeFlags(PhoneAppVersion.OSType.Unknown, emptyList())
        )
    }

    single {
        BlePlatformConfig(
            delayBleConnectionsAfterAppStart = false,
            // Brief settle in WatchManager.cleanup() before releasing the connection slot on
            // disconnect, so we don't re-arm the connect intent while the watch is still tearing the
            // old link down (the flag's documented purpose — "disconnect+reconnect so fast the watch
            // doesn't realize"). Matches the Android default. Independent of the standing-intent
            // reconnect model in BluezGattConnector; kept as a small reconnect hygiene margin.
            delayBleDisconnections = true,
            sendPpogResetOnDisconnection = true,
            // BR/EDR transport implemented (BluezBtClassicConnector). This also hides classic-capable
            // watches (e.g. Time Steel) from the BLE scan so they go through the reliable Classic path,
            // while BLE-native watches (Time 2 / Pebble 2) keep using BLE unaffected.
            supportsBtClassic = true,
            // BlueZ: the watch reads META and writes RESET_REQUEST to our GATT server seconds before
            // ServicesResolved, so register the forward-PPoG device (and publish the PPoG service)
            // before connecting. GattServer.initServer() registers nothing on JVM, so this is the
            // only thing that puts the service up before our Connect() (not necessarily before the
            // link: see GattServer.addServices()).
            registerForwardPpogBeforeConnect = true,
            // Keep the BlueZ GATT server across Bluetooth off/on (the state is real on the JVM since
            // nativeBluetoothStateFlow reads BlueZ): our exported objects and D-Bus connection survive,
            // GattServer re-registers the application itself when an adapter reappears, and the next
            // connect re-adds the service. Closing would tear down that watcher with the connection.
            closeGattServerWhenBtDisabled = false,
        )
    }

    // Per-connection BT Classic connector (mirrors the Android binding). Resolved only for a
    // PebbleBtClassicIdentifier; BLE connections never touch this.
    scope<ConnectionScope> {
        scoped { BluezBtClassicConnector(get(), get(), get()) } bind BtClassicConnector::class
    }

    single { PlatformConfig(syncNotificationApps = false) }

    single<NotificationAppsSync> {
        object : NotificationAppsSync {
            override fun init() {}
        }
    }

    single<PlatformCalendarActionHandler> {
        object : PlatformCalendarActionHandler {
            override suspend fun invoke(pin: TimelinePin, action: BaseAction): TimelineActionResult =
                TimelineActionResult(false, TimelineIcon.ResultDismissed, "")
        }
    }

    // NOTE: no JVM no-op SystemMusicControl / SystemCalendar bindings here. The stoandl daemon
    // overrides both unconditionally (MprisMusicControl / LinuxSystemCalendar), so leaving a silent
    // no-op would only mask a wiring regression — a missing binding fails fast instead. The same
    // holds for NotificationListenerConnection, PlatformNotificationActionHandler, SystemCallLog and
    // SystemGeolocation. See the stoandl fork-nop-ownership convention.

    // Plugin hooks commonMain needs on every platform. Upstream's JVM module is a TODO(), so nothing
    // upstream catches a gap here: without them koin.get<LibPebble>() crashes at startup (see
    // KoinGraphTest). PhoneStatePlugin reads the phone's battery/radio; the JVM actuals are
    // upstream's null-emitting stubs. Backing them with UPower/ModemManager would be a feature of
    // its own, not part of a bump, and is only visible through plugins (enablePlugins, default off).
    single { PhoneBatteryMonitor() }
    single { PhoneNetworkMonitor() }
    // No platform-only plugins on JVM (same as iOS). MusicPlugin is commonMain and could sit on the
    // daemon's MPRIS SystemMusicControl, but that's a feature of its own, not part of a bump.
    single { PlatformPlugins(emptySet()) }
    // No notification images on Linux (yet): registers nothing, so the watch is told the image type
    // is unsupported and stops asking. PhoneCapabilities above deliberately leaves out
    // SupportsImageFetch; serving images or album art means overriding both.
    single<NotificationImageProvider> { NoNotificationImages() }

    single<LegacyPhoneReceiver> {
        object : LegacyPhoneReceiver {
            override fun init(currentCall: MutableStateFlow<Call?>) {}
        }
    }

    single<SystemContacts> {
        object : SystemContacts {
            override fun registerForContactsChanges(): Flow<Unit> = emptyFlow()
            override suspend fun getContacts(): List<SystemContact> = emptyList()
            override fun hasPermission(): Boolean = false
            override suspend fun getContactImage(lookupKey: String): ImageBitmap? = null
        }
    }

    single<OtherPebbleApps> {
        object : OtherPebbleApps {
            override fun otherPebbleCompanionAppsInstalled(): StateFlow<List<OtherPebbleApp>> =
                MutableStateFlow(emptyList())
        }
    }

    single<ClassicScanner> { BluezClassicScanner() }
}
