package io.rebble.libpebblecommon

import java.io.File

/**
 * Base config/data directory for the stoandl daemon on JVM, honouring `XDG_CONFIG_HOME` (falling
 * back to `~/.config`). Every JVM persistent store — the Room database, locker `.pbw` cache, PKJS
 * local storage, firmware downloads — lives under here, so a single `XDG_CONFIG_HOME` override
 * relocates all of them together (and matches where stoandl itself reads `stoandl.conf`).
 */
fun stoandlConfigDir(): File {
    val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
    val base = xdg ?: (System.getProperty("user.home") + "/.config")
    return File(base, "stoandl")
}
