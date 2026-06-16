package io.rebble.libpebblecommon.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanningTest {
    @Test
    fun dualModeClassicBridgeNamesAreRecognised() {
        // Classic-capable watches expose a "Pebble … LE …" dual-mode bridge name.
        assertTrue(isDualModeClassicBridgeName("Pebble Time LE 1A2B"))
        assertTrue(isDualModeClassicBridgeName("Pebble Time Steel LE C3D4"))
        assertTrue(isDualModeClassicBridgeName("pebble le")) // case-insensitive
    }

    @Test
    fun nonBridgeNamesAreNotRecognised() {
        assertFalse(isDualModeClassicBridgeName("Pebble Time Steel")) // no LE token
        assertFalse(isDualModeClassicBridgeName("Pebble 2 SE"))       // BLE-native, no LE token
        assertFalse(isDualModeClassicBridgeName("Pebble Sleeve"))     // "le" inside a word, not \bLE\b
        assertFalse(isDualModeClassicBridgeName("My LE Speaker"))     // doesn't start with "Pebble"
        assertFalse(isDualModeClassicBridgeName(""))
    }
}
