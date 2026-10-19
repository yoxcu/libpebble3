package io.rebble.libpebblecommon.js

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import java.util.concurrent.ConcurrentHashMap

/**
 * The process's GraalJS engines, for [GraalJsRunner] (PKJS) and [JsEngine] (plugins). Contexts on
 * one engine share parsed code, so a session starts in milliseconds; a context on its own engine
 * pays for a whole engine every time.
 *
 * One engine per host-access policy, not one in all: Truffle's host language lives in the engine
 * and refuses a context whose [HostAccess] differs from the first one's ("Found different host
 * access configuration for a context with a shared engine"), and PKJS and plugins each keep their
 * own allow-list. [HostAccess] compares by content, so a policy built once maps to one engine.
 */
internal object GraalEngines {
    private val engines = ConcurrentHashMap<HostAccess, Engine>()

    /** A `js` context builder on the engine for [hostAccess], with that host access already set. */
    fun contextBuilder(hostAccess: HostAccess): Context.Builder =
        Context.newBuilder("js")
            .engine(engines.computeIfAbsent(hostAccess) { newEngine() })
            .allowHostAccess(hostAccess)

    // Stock OpenJDK runs Truffle interpreter-only; the warning is noise for every session.
    private fun newEngine(): Engine =
        Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()
}
