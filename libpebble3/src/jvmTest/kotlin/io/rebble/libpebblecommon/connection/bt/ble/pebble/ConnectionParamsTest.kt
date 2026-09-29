package io.rebble.libpebblecommon.connection.bt.ble.pebble

import io.rebble.libpebblecommon.BleConnParamSet
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectionParams.Companion.encode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionParamsTest {
    private val idle = BleConnParamSet(minIntervalMs = 500.0, maxIntervalMs = 520.0, slaveLatency = 0, supervisionTimeoutMs = 6000)
    private val fast = BleConnParamSet(minIntervalMs = 15.0, maxIntervalMs = 15.0, slaveLatency = 0, supervisionTimeoutMs = 6000)

    @Test
    fun encodesPpsParamSet() {
        // 500 ms = 400 x 1.25 ms (0x0190 LE), delta 20 ms = 16, latency 0, 6000 ms / 30 = 200 (0xC8)
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x01, 0x10, 0x00, 0xC8.toByte()), idle.encode())
        assertArrayEquals(byteArrayOf(0x0C, 0x00, 0x00, 0x00, 0xC8.toByte()), fast.encode())
    }

    @Test
    fun paramMgmtWriteIsSeventeenBytesInSlotOrder() {
        val v = ConnectionParams.paramMgmtWrite(idle, idle, fast)
        assertEquals(17, v.size) // must fit the 20-byte minimum-MTU payload
        assertEquals(0x00.toByte(), v[0]) // SET_REMOTE_PARAM_MGMT_SETTINGS
        assertEquals(0x00.toByte(), v[1]) // watch manages, using our sets
        assertArrayEquals(idle.encode(), v.copyOfRange(2, 7))   // MAX
        assertArrayEquals(idle.encode(), v.copyOfRange(7, 12))  // MIDDLE
        assertArrayEquals(fast.encode(), v.copyOfRange(12, 17)) // MIN
    }

    @Test
    fun validateRejectsWhatWatchOrHostWouldRefuse() {
        assertNull(idle.validate())
        assertNull(fast.validate())
        assertNotNull(idle.copy(minIntervalMs = 5.0).validate())
        assertNotNull(idle.copy(maxIntervalMs = 900.0).validate())          // delta > 318.75 ms
        assertNotNull(idle.copy(supervisionTimeoutMs = 1000).validate())    // <= 2 x 520 ms
        assertNotNull(idle.copy(slaveLatency = 5, supervisionTimeoutMs = 6000).validate()) // 2 x 6 x 520 ms
        assertNotNull(idle.copy(supervisionTimeoutMs = 9000).validate())    // does not fit one byte of 30 ms
    }
}
