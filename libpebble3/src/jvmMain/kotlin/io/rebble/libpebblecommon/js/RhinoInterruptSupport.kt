package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global Rhino ContextFactory that supports per-JS-thread interruption.
 *
 * Each RhinoJsRunner registers its jsThread with an AtomicBoolean interrupt flag.
 * The factory sets instructionObserverThreshold so Rhino calls observeInstructionCount
 * every 100 k interpreted bytecodes. If the flag is set, execution is aborted by throwing
 * an exception — allowing long-running showConfiguration JS to be interrupted after timeout.
 *
 * Note: this fires BETWEEN JS bytecode instructions, not within native regex execution.
 * It will interrupt long forEach/map loops but cannot cut short a single catastrophically
 * slow regex call mid-execution.
 */
internal object RhinoInterruptSupport {
    private val logger = Logger.withTag("RhinoInterruptSupport")
    private val flags = ConcurrentHashMap<Long, AtomicBoolean>()
    @Volatile private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                ContextFactory.initGlobal(object : ContextFactory() {
                    override fun makeContext(): Context =
                        super.makeContext().also { it.instructionObserverThreshold = 100_000 }

                    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                        if (flags[Thread.currentThread().id]?.get() == true) {
                            throw RuntimeException("JS execution interrupted after timeout")
                        }
                    }
                })
                installed = true
            } catch (e: Exception) {
                logger.w { "Could not install Rhino interrupt support (factory already set): ${e.message}" }
            }
        }
    }

    fun register(threadId: Long, flag: AtomicBoolean) { flags[threadId] = flag }
    fun unregister(threadId: Long) { flags.remove(threadId) }
}
