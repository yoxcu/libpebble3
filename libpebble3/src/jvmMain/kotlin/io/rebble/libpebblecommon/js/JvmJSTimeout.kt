package io.rebble.libpebblecommon.js

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class JvmJSTimeout(
    private val scope: CoroutineScope,
    private val jsThread: ExecutorCoroutineDispatcher,
    private val evalFn: (String) -> Unit,
) {
    private val idCounter = AtomicInteger(0)
    private val jobs = ConcurrentHashMap<Int, Job>()

    fun setTimeout(delayMs: Double): Int {
        val id = idCounter.incrementAndGet()
        val job = scope.launch {
            delay(delayMs.toLong().coerceAtLeast(0))
            withContext(jsThread) {
                if (jobs.remove(id) != null) evalFn("_LibPebbleTriggerTimeout($id)")
            }
        }
        jobs[id] = job
        return id
    }

    fun clearTimeout(id: Int) { jobs.remove(id)?.cancel() }

    fun setInterval(delayMs: Double): Int {
        val id = idCounter.incrementAndGet()
        val job = scope.launch {
            while (isActive && jobs.containsKey(id)) {
                delay(delayMs.toLong().coerceAtLeast(1))
                if (!jobs.containsKey(id)) break
                withContext(jsThread) { evalFn("_LibPebbleTriggerInterval($id)") }
            }
        }
        jobs[id] = job
        return id
    }

    fun clearInterval(id: Int) { jobs.remove(id)?.cancel() }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}
