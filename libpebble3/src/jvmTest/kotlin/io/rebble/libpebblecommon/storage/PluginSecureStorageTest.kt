package io.rebble.libpebblecommon.storage

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class PluginSecureStorageTest {
    private class MapStorage : SecureStorage {
        val values = mutableMapOf<String, String>()
        override suspend fun get(key: String) = values[key]
        override suspend fun set(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun destroy() = values.clear()
    }

    @Test
    fun eachPluginGetsTheHostStoreForItsUuid() = runBlocking {
        val stores = mutableMapOf<Uuid, MapStorage>()
        val context = AppContext(pluginSecrets = { uuid -> stores.getOrPut(uuid) { MapStorage() } })
        val a = Uuid.random()
        val b = Uuid.random()

        pluginSecureStorage(context, a).set("token", "secret")

        assertEquals("secret", pluginSecureStorage(context, a).get("token"))
        assertNull(pluginSecureStorage(context, b).get("token"))
        pluginSecureStorage(context, a).destroy()
        assertNull(stores.getValue(a).values["token"])
    }

    @Test
    fun withoutAHostStoreEveryCallFails() = runBlocking<Unit> {
        // Building it must not fail: every JsPlugin does, whether or not it uses secure storage.
        val storage = pluginSecureStorage(AppContext(), Uuid.random())

        assertFailsWith<IllegalStateException> { storage.set("token", "secret") }
        assertFailsWith<IllegalStateException> { storage.get("token") }
        assertFailsWith<IllegalStateException> { storage.destroy() }
    }
}
