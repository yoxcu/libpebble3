package io.rebble.libpebblecommon.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Fork addition (stoandl, for hosts that suspend aggressively — e.g. an s2idle phone that keeps the
 * BLE link up inside the controller while the SoC sleeps). One process-wide view of watch traffic
 * that is still owed or awaited, written by the per-connection transport/BlobDB code and read by the
 * host app, plus the host's "about to suspend" hint.
 *
 * Why the host needs it: a phone that wakes for a push message has a few seconds before it suspends
 * again. The notification that push produced goes out through BlobDB → PPoG, and if the system
 * suspends between the BlobDB insert and the watch's ACK the delivery stalls until the next wake. A
 * host holding a logind *delay* inhibitor can use [busy] to release it only once nothing is in flight
 * (bounded by logind's InhibitDelayMaxSec), instead of guessing with a fixed sleep.
 *
 * Writers report a pending count per *source* (a stable key such as `"ppog:<watch>"`); a count of 0
 * removes the source. Sources must report 0 when their connection ends (use try/finally), otherwise
 * [busy] would stick at true and every host suspend would wait out the full delay.
 */
class WatchLinkActivity {
    private val _pending = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Pending work per source (only sources with a count > 0). Mostly for logging. */
    val pending: StateFlow<Map<String, Int>> = _pending.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** True while any source reports pending work. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _hostSuspending = MutableStateFlow(false)

    /**
     * Set by the host between logind's `PrepareForSleep(true)` and the matching `PrepareForSleep(false)`.
     * libpebble3 doesn't read it; the host does, to hold off new link work (e.g. discovery) until the resume.
     */
    val hostSuspending: StateFlow<Boolean> = _hostSuspending.asStateFlow()

    fun report(source: String, count: Int) {
        _pending.update { current ->
            when {
                count > 0 && current[source] != count -> current + (source to count)
                count <= 0 && source in current -> current - source
                else -> current
            }
        }
        // Re-derive [busy] from the latest map. Loop until the map did not change under us, so a
        // concurrent report() can never leave a stale value behind (its own write is then re-checked).
        while (true) {
            val snapshot = _pending.value
            _busy.value = snapshot.isNotEmpty()
            if (_pending.value == snapshot) break
        }
    }

    fun setHostSuspending(suspending: Boolean) {
        _hostSuspending.value = suspending
    }

    /** One-line summary of what is still pending (e.g. `ppog:AA:BB…=2, blobdb:AA:BB…=1`), or "none". */
    fun summary(): String =
        _pending.value.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none" }
}
