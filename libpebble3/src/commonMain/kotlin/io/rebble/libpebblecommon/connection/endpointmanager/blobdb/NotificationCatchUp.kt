package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Fork addition (stoandl, for hosts whose watch link drops while notifications keep arriving — a phone
 * that loses the link on every suspend and wakes for push messages, a watch out of range). Decides, per
 * connection, how old a notification BlobDB may still send.
 *
 * The notification DB is `onlyInsertAfter`: upstream sends only notifications created after this
 * connection's sync started, so a reconnect never floods the watch — and everything posted while the
 * watch was away is silently never delivered. With a non-zero
 * [io.rebble.libpebblecommon.NotificationConfig.missedNotificationCatchUpMs] a (re)connecting watch also
 * gets the ones it has not received that are at most that old, but never ones created before
 *  - libpebble started (this object is created with WatchManager), so a previous run's backlog stays put;
 *  - the watch's last *fresh start* in this process — its first connection after pairing, or an
 *    unfaithful watch — where BlobDB wipes the watch's databases and the watch starts clean. That
 *    connection itself only gets what was created since it began (upstream: since its sync started).
 *
 * Nothing the watch already has is sent again: BlobDB tracks sync state per record and watch. A record
 * attempted on an earlier connection but never acknowledged (link dropped, watch said try later) is
 * retried while it is inside the window. Each watch catches up on its own, so a watch switched to also
 * gets what another watch already showed inside the window. `0` is exactly upstream.
 */
class NotificationCatchUp(
    timeProvider: TimeProvider,
    private val notificationConfigFlow: NotificationConfigFlow,
) {
    private val startedAt: Instant = timeProvider.now()

    // Last fresh start per watch (PebbleIdentifier.asString), for the process lifetime.
    private val freshStarts = MutableStateFlow<Map<String, Instant>>(emptyMap())

    /**
     * A connection of [watch] that began at [at] wipes the watch's databases (first connection after
     * pairing, or an unfaithful watch). Call it as soon as that is decided, before anything can drop the
     * connection: the next connection is no longer a fresh one, but the local sync state is already gone.
     */
    fun markFreshStart(watch: PebbleIdentifier, at: Instant) {
        freshStarts.update { it + (watch.asString to at) }
    }

    /**
     * The `insertOnlyAfter` threshold for the notification DB on a connection of [watch] whose sync starts
     * at [now]: notifications with a later timestamp are sent.
     */
    fun insertOnlyAfter(watch: PebbleIdentifier, now: Instant): Instant = catchUpThreshold(
        now = now,
        floor = freshStarts.value[watch.asString] ?: startedAt,
        window = notificationConfigFlow.value.missedNotificationCatchUpMs.milliseconds,
    )

    /** Drops [watch]'s state once it is forgotten (a re-paired watch starts fresh anyway). */
    fun forget(watch: PebbleIdentifier) {
        freshStarts.update { it - watch.asString }
    }
}

/**
 * The catch-up rule of [NotificationCatchUp]: [now] (upstream) when [window] is not positive, else the
 * later of [floor] and [now] − [window] — but never later than [now] (a floor from a clock that has since
 * gone backwards).
 */
internal fun catchUpThreshold(now: Instant, floor: Instant, window: Duration): Instant =
    if (window <= Duration.ZERO) now else minOf(now, maxOf(floor, now - window))
