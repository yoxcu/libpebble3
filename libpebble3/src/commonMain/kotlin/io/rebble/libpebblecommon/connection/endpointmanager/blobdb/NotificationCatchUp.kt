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
 * Time the host kept the watches disconnected on purpose ([pauseStarted]/[pauseEnded], the WatchManager
 * hold) doesn't count towards the window: after a night's pause the watch gets everything posted during
 * it, plus the window before it, as if it had stayed connected in Quiet Time.
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

    // Host-side pauses, oldest first; the last one is open (end null) while the pause lasts.
    private val pauses = MutableStateFlow<List<Pause>>(emptyList())

    /** The host started keeping every watch disconnected at [at]. */
    fun pauseStarted(at: Instant) {
        pauses.update { list ->
            if (list.lastOrNull()?.let { it.end == null } == true) list
            else (list + Pause(at, null)).takeLast(MAX_PAUSES)
        }
    }

    /** The pause begun by [pauseStarted] ended at [at]. */
    fun pauseEnded(at: Instant) {
        pauses.update { list ->
            val open = list.lastOrNull()?.takeIf { it.end == null } ?: return@update list
            list.dropLast(1) + open.copy(end = maxOf(open.start, at))
        }
    }

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
        pauses = pauses.value,
    )

    /** Drops [watch]'s state once it is forgotten (a re-paired watch starts fresh anyway). */
    fun forget(watch: PebbleIdentifier) {
        freshStarts.update { it - watch.asString }
    }
}

/** A host-side pause of all watch connections; [end] is null while it lasts. */
internal data class Pause(val start: Instant, val end: Instant?)

private const val MAX_PAUSES = 16

/**
 * The catch-up rule of [NotificationCatchUp]: [now] (upstream) when [window] is not positive, else the
 * later of [floor] and the point [window] of unpaused time before [now] — [pauses] (oldest first, the last
 * one possibly open) are skipped, not counted — but never later than [now] (a floor from a clock that has
 * since gone backwards).
 */
internal fun catchUpThreshold(
    now: Instant,
    floor: Instant,
    window: Duration,
    pauses: List<Pause> = emptyList(),
): Instant {
    if (window <= Duration.ZERO) return now
    // Walk back from now, spending the window only on the gaps between pauses.
    var cursor = now
    var left = window
    for (pause in pauses.asReversed()) {
        if (pause.start >= cursor) continue
        val gap = cursor - minOf(pause.end ?: cursor, cursor)
        if (gap >= left) break
        left -= gap
        cursor = pause.start
    }
    return minOf(now, maxOf(floor, cursor - left))
}
