package io.rebble.libpebblecommon.connection.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BlueZDiscoveryTest {
    @Test
    fun discoveryAlreadyHeldByThisConnectionIsNotAFailure() {
        assertNull(discoveryStartFailure(RuntimeException("Operation already in progress")))
        assertNull(discoveryStartFailure(RuntimeException("org.bluez.Error.InProgress")))
    }

    @Test
    fun otherStartDiscoveryErrorsFailTheScanWithBlueZsText() {
        val e = discoveryStartFailure(RuntimeException("Resource Not Ready"))
        assertNotNull(e)
        assertEquals("Bluetooth discovery didn't start (Resource Not Ready)", e!!.message)
    }
}
