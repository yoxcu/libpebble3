package io.rebble.libpebblecommon.connection.bt.ble.pebble

import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattService
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattWriteType
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConnectionParamsTest {
    // Fork: no "the phone manages" write, with or without the characteristic (see ConnectionParams).
    @Test
    fun neverWritesTheCharacteristic() = runTest {
        val params = ConnectionParams(ConnectionCoroutineScope(backgroundScope.coroutineContext))
        val withCharacteristic = FakeGatt(emptyFlow())
        val without = FakeGatt(null)
        assertTrue(params.subscribeAndConfigure(withCharacteristic))
        assertFalse(params.subscribeAndConfigure(without))
        assertEquals(0, withCharacteristic.writes + without.writes)
    }

    private class FakeGatt(private val subscription: Flow<ByteArray>?) : ConnectedGattClient {
        var writes = 0

        override fun subscribeToCharacteristic(
            serviceUuid: Uuid,
            characteristicUuid: Uuid,
            onSubscription: (suspend () -> Unit)?,
        ): Flow<ByteArray>? = subscription

        override suspend fun writeCharacteristic(
            serviceUuid: Uuid,
            characteristicUuid: Uuid,
            value: ByteArray,
            writeType: GattWriteType,
        ): Boolean {
            writes++
            return true
        }

        override suspend fun discoverServices() = true
        override suspend fun isBonded() = true
        override suspend fun readCharacteristic(serviceUuid: Uuid, characteristicUuid: Uuid): ByteArray? = null
        override val services: List<GattService>? = null
        override suspend fun requestMtu(mtu: Int) = mtu
        override suspend fun getMtu() = 23
        override suspend fun refreshServicesNative() = true
        override fun close() {}
    }
}
