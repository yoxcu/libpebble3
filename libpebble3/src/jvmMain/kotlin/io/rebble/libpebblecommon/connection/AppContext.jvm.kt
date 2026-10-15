package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.storage.PluginSecrets

/**
 * What the JVM host hands libpebble3 in place of an Android Context.
 *
 * [pluginSecrets] backs plugins' `Pebble.secureStorage` (and its cleanup when an app is removed).
 * Without it every secure-storage call fails, rather than dropping the secret.
 */
actual class AppContext(
    val pluginSecrets: PluginSecrets? = null,
)
