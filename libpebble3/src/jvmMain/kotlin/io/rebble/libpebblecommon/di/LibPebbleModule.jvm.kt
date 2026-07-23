package io.rebble.libpebblecommon.di

import androidx.compose.ui.graphics.ImageBitmap
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.calls.LegacyPhoneReceiver
import io.rebble.libpebblecommon.calendar.PlatformCalendarActionHandler
import io.rebble.libpebblecommon.connection.AppContext
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
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single { AppContext() }

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
    // no-op would only mask a wiring regression — a missing binding fails fast instead. See the
    // stoandl fork-nop-ownership convention.

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
