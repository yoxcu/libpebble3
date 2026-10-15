package io.rebble.libpebblecommon.storage

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import kotlin.uuid.Uuid

/**
 * The host's secret store, one [SecureStorage] per plugin. The JVM has no platform keystore, so the
 * host supplies one (stoandl: the Secret Service keyring) through [AppContext.pluginSecrets].
 * [SecureStorage.destroy] must remove everything stored for that plugin: it runs on app removal.
 */
fun interface PluginSecrets {
    fun forPlugin(pluginUuid: Uuid): SecureStorage
}

internal actual fun pluginSecureStorage(appContext: AppContext, pluginUuid: Uuid): SecureStorage =
    appContext.pluginSecrets?.forPlugin(pluginUuid) ?: NoPluginSecrets(pluginUuid)

/**
 * No host store: every call fails (the plugin's promise rejects, the locker logs it). Built eagerly
 * by every JsPlugin, so failing here rather than at construction keeps plugins that never touch
 * secure storage working.
 */
private class NoPluginSecrets(private val pluginUuid: Uuid) : SecureStorage {
    override suspend fun get(key: String): String? = unavailable()
    override suspend fun set(key: String, value: String) = unavailable()
    override suspend fun remove(key: String) = unavailable()
    override suspend fun destroy() = unavailable()

    private fun unavailable(): Nothing {
        val message = "No secure storage on this host (AppContext.pluginSecrets is unset); plugin $pluginUuid"
        logger.e { message }
        throw IllegalStateException(message)
    }

    private companion object {
        val logger = Logger.withTag("PluginSecureStorage")
    }
}
