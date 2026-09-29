package io.rebble.libpebblecommon.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class WatchLinkActivityTest {
    @Test
    fun busyFollowsSources() {
        val a = WatchLinkActivity()
        assertFalse(a.busy.value)
        a.report("ppog:w", 2)
        a.report("blobdb:w", 1)
        assertTrue(a.busy.value)
        assertEquals("ppog:w=2, blobdb:w=1", a.summary())
        a.report("ppog:w", 0)
        assertTrue(a.busy.value)
        a.report("blobdb:w", 0)
        assertFalse(a.busy.value)
        assertEquals("none", a.summary())
    }

    @Test
    fun concurrentReportsNeverLeaveStaleBusy() {
        val a = WatchLinkActivity()
        repeat(200) { round ->
            val start = CountDownLatch(1)
            val threads = (0 until 4).map { t ->
                thread {
                    start.await()
                    repeat(50) { i -> a.report("s$t", if (i % 2 == 0) 1 else 0) }
                    a.report("s$t", 0)
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertFalse("round $round: ${a.summary()}", a.busy.value)
        }
    }
}
