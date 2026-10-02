package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import io.rebble.libpebblecommon.NotificationConfig
import io.rebble.libpebblecommon.asFlow
import io.rebble.libpebblecommon.connection.PebbleSocketIdentifier
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class NotificationCatchUpTest {
    private val t0 = Instant.fromEpochMilliseconds(1_790_688_000_000)
    private val watch = PebbleSocketIdentifier("ED:86:0A:D4:B3:49")
    private val other = PebbleSocketIdentifier("B0:B4:48:B6:1E:81")

    private class FixedTime(private val at: Instant) : TimeProvider {
        override fun now(): Instant = at
    }

    private fun catchUp(window: Duration, startedAt: Instant = t0) = NotificationCatchUp(
        FixedTime(startedAt),
        NotificationConfig(missedNotificationCatchUpMs = window.inWholeMilliseconds).asFlow(),
    )

    @Test
    fun zeroWindowIsUpstream() {
        val now = t0 + 30.minutes
        assertEquals(now, catchUpThreshold(now = now, floor = t0, window = Duration.ZERO))
        val c = catchUp(Duration.ZERO)
        assertEquals(now, c.insertOnlyAfter(watch, now))
        c.markFreshStart(watch, now - 20.seconds)
        assertEquals(now, c.insertOnlyAfter(watch, now))
    }

    @Test
    fun shortDisconnectCatchesUpToLibpebbleStart() {
        val c = catchUp(10.minutes)
        // Watch reconnects 2 min after libpebble started: everything since the start is eligible.
        assertEquals(t0, c.insertOnlyAfter(watch, t0 + 2.minutes))
    }

    @Test
    fun longDisconnectIsBoundedByTheWindow() {
        val c = catchUp(10.minutes)
        val now = t0 + 3.minutes + 60.minutes
        assertEquals(now - 10.minutes, c.insertOnlyAfter(watch, now))
    }

    @Test
    fun freshStartIsAFloorForThatWatchOnly() {
        val c = catchUp(10.minutes)
        val paired = t0 + 5.minutes
        // First connection after pairing: its sync (after the handshake) sends only what is newer than
        // the connection itself.
        c.markFreshStart(watch, paired)
        assertEquals(paired, c.insertOnlyAfter(watch, paired + 20.seconds))
        // Its next reconnect catches up only to the pairing, not to libpebble's start.
        assertEquals(paired, c.insertOnlyAfter(watch, paired + 1.minutes))
        // …and later only within the window.
        val later = paired + 30.minutes
        assertEquals(later - 10.minutes, c.insertOnlyAfter(watch, later))
        // Another watch still uses libpebble's start.
        assertEquals(t0, c.insertOnlyAfter(other, t0 + 6.minutes))
    }

    @Test
    fun freshStartHoldsWhenItsConnectionDropsBeforeSyncing() {
        val c = catchUp(10.minutes)
        val paired = t0 + 5.minutes
        // The fresh connection wipes the sync state, then drops during its handshake: no threshold is
        // ever taken for it. The next connection is not fresh (the watch is known by now), yet nothing
        // from before the pairing is sent.
        c.markFreshStart(watch, paired)
        assertEquals(paired, c.insertOnlyAfter(watch, paired + 2.minutes))
    }

    @Test
    fun forgetDropsTheFreshStart() {
        val c = catchUp(10.minutes)
        c.markFreshStart(watch, t0 + 5.minutes)
        c.forget(watch)
        assertEquals(t0, c.insertOnlyAfter(watch, t0 + 6.minutes))
    }

    @Test
    fun neverLaterThanNow() {
        // Clock went backwards after the floor was taken: stay at "now", like upstream.
        val now = t0 + 1.minutes
        assertEquals(now, catchUpThreshold(now = now, floor = t0 + 2.minutes, window = 10.minutes))
        assertEquals(now - 30.seconds, catchUpThreshold(now = now, floor = now - 30.seconds, window = 10.minutes))
    }

    @Test
    fun pausedTimeDoesNotCountTowardsTheWindow() {
        val window = 60.minutes
        val start = t0 + 2.minutes
        val pauseStart = start + 3.minutes  // 23:00
        val pauseEnd = pauseStart + 480.minutes // 07:00
        val c = catchUp(window, startedAt = start - 10.minutes)
        c.pauseStarted(pauseStart)
        // During the pause (no connection, but the rule must still hold): the whole pause is skipped.
        assertEquals(start - 10.minutes, c.insertOnlyAfter(watch, pauseStart + 100.minutes))
        c.pauseEnded(pauseEnd)
        // Reconnect 30 s after the pause: the window covers 30 s after it and 59.5 min before it — bounded
        // here by libpebble's start.
        assertEquals(start - 10.minutes, c.insertOnlyAfter(watch, pauseEnd + 30.seconds))
    }

    @Test
    fun windowReachesBeforeAPause() {
        val pause = Pause(t0 + 120.minutes, t0 + 600.minutes)
        val now = pause.end!! + 10.minutes
        // 10 min after the pause, 50 min before it.
        assertEquals(pause.start - 50.minutes, catchUpThreshold(now, t0, 60.minutes, listOf(pause)))
        // An open pause: everything since it, plus the window before it.
        val open = Pause(t0 + 120.minutes, null)
        assertEquals(open.start - 60.minutes, catchUpThreshold(t0 + 300.minutes, t0, 60.minutes, listOf(open)))
        // A pause older than the window changes nothing.
        val later = pause.end!! + 120.minutes
        assertEquals(later - 60.minutes, catchUpThreshold(later, t0, 60.minutes, listOf(pause)))
        // Two pauses: 10 min after the second, 20 min between them, 30 min before the first.
        val p1 = Pause(t0 + 100.minutes, t0 + 200.minutes)
        val p2 = Pause(t0 + 220.minutes, t0 + 400.minutes)
        assertEquals(p1.start - 30.minutes, catchUpThreshold(p2.end!! + 10.minutes, t0, 60.minutes, listOf(p1, p2)))
        // Window 0 stays upstream.
        assertEquals(now, catchUpThreshold(now, t0, Duration.ZERO, listOf(pause)))
    }
}
