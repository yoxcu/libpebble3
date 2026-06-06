package io.rebble.libpebblecommon.di

import androidx.compose.ui.graphics.ImageBitmap
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.calls.LegacyPhoneReceiver
import io.rebble.libpebblecommon.calls.MissedCall
import io.rebble.libpebblecommon.calls.SystemCallLog
import io.rebble.libpebblecommon.calendar.CalendarEvent
import io.rebble.libpebblecommon.calendar.PlatformCalendarActionHandler
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.OtherPebbleApp
import io.rebble.libpebblecommon.connection.OtherPebbleApps
import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.connection.PlatformFlags
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.classic.transport.ClassicScanner
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.contacts.SystemContact
import io.rebble.libpebblecommon.contacts.SystemContacts
import io.rebble.libpebblecommon.database.entity.BaseAction
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.TimelinePin
import io.rebble.libpebblecommon.music.PlaybackStatus
import io.rebble.libpebblecommon.music.SystemMusicControl
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.uuid.Uuid
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
            delayBleDisconnections = false,
            sendPpogResetOnDisconnection = true,
            supportsBtClassic = false,
        )
    }

    single { PlatformConfig(syncNotificationApps = false) }

    // Host app overrides this with a DBus-based implementation via Koin module override.
    single<NotificationListenerConnection> {
        object : NotificationListenerConnection {
            override fun init(libPebble: LibPebble) {}
        }
    }

    single<PlatformNotificationActionHandler> {
        object : PlatformNotificationActionHandler {
            override suspend fun invoke(
                itemId: Uuid,
                action: BaseAction,
                attributes: List<TimelineItem.Attribute>,
            ): TimelineActionResult = TimelineActionResult(false, TimelineIcon.ResultDismissed, "")
        }
    }

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

    single<SystemMusicControl> {
        object : SystemMusicControl {
            override val playbackState: StateFlow<PlaybackStatus?> = MutableStateFlow(null)
            override fun play() {}
            override fun pause() {}
            override fun playPause() {}
            override fun nextTrack() {}
            override fun previousTrack() {}
            override fun volumeUp() {}
            override fun volumeDown() {}
        }
    }

    single<SystemCalendar> {
        object : SystemCalendar {
            override suspend fun getCalendars(): List<CalendarEntity> = emptyList()
            override suspend fun getCalendarEvents(
                calendar: CalendarEntity,
                startDate: Instant,
                endDate: Instant,
            ): List<CalendarEvent> = emptyList()
            override suspend fun enableSyncForCalendar(calendar: CalendarEntity) {}
            override fun registerForCalendarChanges(): Flow<Unit>? = null
            override fun hasPermission(): Boolean = false
            override fun supportsPinActions(): Boolean = false
        }
    }

    single<SystemCallLog> {
        object : SystemCallLog {
            override suspend fun getMissedCalls(start: Instant): List<MissedCall> = emptyList()
            override fun registerForMissedCallChanges(): Flow<Unit> = emptyFlow()
            override fun hasPermission(): Boolean = false
        }
    }

    single<LegacyPhoneReceiver> {
        object : LegacyPhoneReceiver {
            override fun init(currentCall: MutableStateFlow<Call?>) {}
        }
    }

    single<SystemGeolocation> {
        object : SystemGeolocation {
            override suspend fun getCurrentPosition(
                maximumAge: Duration?,
                timeout: Duration?,
                highAccuracy: Boolean,
            ): GeolocationPositionResult = GeolocationPositionResult.Error("Not supported on Linux")
            override suspend fun watchPosition(
                interval: Duration,
                highAccuracy: Boolean,
            ): Flow<GeolocationPositionResult> = emptyFlow()
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

    single<ClassicScanner> {
        object : ClassicScanner {
            override fun scan(): Flow<io.rebble.libpebblecommon.connection.PebbleScanResult> =
                emptyFlow()
        }
    }
}
