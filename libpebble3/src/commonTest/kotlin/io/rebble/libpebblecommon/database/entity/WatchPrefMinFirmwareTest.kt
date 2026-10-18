package io.rebble.libpebblecommon.database.entity

import coredev.BlobDatabase
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.ValueParams
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.blobdb.DbWrite
import io.rebble.libpebblecommon.services.blobdb.WriteType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant

private val TIMESTAMP = Instant.fromEpochSeconds(1760745600)

private fun params(tag: String) = ValueParams(
    platform = WatchType.EMERY,
    capabilities = emptySet(),
    firmwareVersion = FirmwareVersion.from(
        tag = tag,
        isRecovery = false,
        gitHash = "",
        timestamp = Instant.DISTANT_PAST,
        isDualSlot = false,
        isSlot0 = false,
    )!!,
    libPebbleConfigFlow = LibPebbleConfigFlow(MutableStateFlow(LibPebbleConfig())),
)

private fun item(pref: WatchPref<*>, value: String) = WatchPrefItem(
    id = pref.id,
    value = value,
    timestamp = TIMESTAMP.asMillisecond(),
)

class WatchPrefMinFirmwareTest {
    private val pref = BoolWatchPref.QuietTimeNotificationBacklight

    @Test
    fun quietTimeNotificationBacklightIsTheFirmwaresKey() {
        assertSame(pref, WatchPref.from("dndNotificationBacklight"))
        assertEquals(true, pref.defaultValue)
        assertEquals(WatchPrefMinFirmware(4, 41), pref.minFirmware)
    }

    @Test
    fun encodesAsOneByteAndReadsBackFromAWatchWrite() {
        val fw = params("v4.41.0")
        for ((value, byte) in listOf(false to 0u.toUByte(), true to 1u.toUByte())) {
            val sent = item(pref, pref.encodeValue(value))
            assertContentEquals(ubyteArrayOf(byte), sent.value(fw))
            val write = DbWrite(
                token = 1.toUShort(),
                database = BlobDatabase.WatchPrefs,
                timestamp = TIMESTAMP.epochSeconds.toUInt(),
                key = sent.key(),
                value = ubyteArrayOf(byte),
                writeType = WriteType.WriteBack,
            )
            val received = write.asWatchPrefItem(fw)
            assertEquals(sent, received)
            assertEquals(value, pref.decodeValue(received!!.value))
        }
    }

    @Test
    fun isNotSentToFirmwareThatRejectsIt() {
        val sent = item(pref, pref.encodeValue(false))
        assertNull(sent.value(params("v4.40.0")))
        assertNull(sent.value(params("v4.38.2")))
        assertNull(sent.value(params("v3.12.3")))
        assertNotNull(sent.value(params("v4.41.0-rc1")))
        assertNotNull(sent.value(params("v4.41.2")))
        assertNotNull(sent.value(params("v5.0")))
    }

    @Test
    fun splitTextSizesWaitFor439() {
        val size = item(EnumWatchPref.SystemTextSize, ContentSize.Default.code.toString())
        assertNull(size.value(params("v4.38.2")))
        assertNotNull(size.value(params("v4.39.0")))
        assertNull(item(EnumWatchPref.NotificationTextSize, "0").value(params("v4.38.1")))
    }

    @Test
    fun everyKeyWhitelistedAfter4_10CarriesItsRelease() {
        // The first PebbleOS tag whose settings_blob_db.c whitelist has the key.
        val expected = mapOf(
            "lightPreset" to WatchPrefMinFirmware(4, 22),
            "lightDynamicMode" to WatchPrefMinFirmware(4, 22),
            "language" to WatchPrefMinFirmware(4, 28),
            "musicShowAlbumArt" to WatchPrefMinFirmware(4, 34),
            "dndAutoDismiss" to WatchPrefMinFirmware(4, 37),
            "unitsWind" to WatchPrefMinFirmware(4, 37),
            "systemTextSize" to WatchPrefMinFirmware(4, 39),
            "notifTextSize" to WatchPrefMinFirmware(4, 39),
            "dndNotificationBacklight" to WatchPrefMinFirmware(4, 41),
        )
        val gated = WatchPref.enumeratePrefs().mapNotNull { p -> p.minFirmware?.let { p.id to it } }.toMap()
        assertEquals(expected, gated)
    }

    @Test
    fun olderGatesHoldBackUntilTheirRelease() {
        val autoDismiss = item(BoolWatchPref.QuietTimeAutoDismiss, "1")
        assertNull(autoDismiss.value(params("v4.36.2")))
        assertNotNull(autoDismiss.value(params("v4.37.0")))
        val wind = item(EnumWatchPref.WindSpeed, WindUnits.entries.first().code.toString())
        assertNull(wind.value(params("v4.36.2")))
        assertNotNull(wind.value(params("v4.37.0")))
        val albumArt = item(BoolWatchPref.MusicShowAlbumArt, "1")
        assertNull(albumArt.value(params("v4.33.2")))
        assertNotNull(albumArt.value(params("v4.34.0")))
        val language = item(EnumWatchPref.Language, WatchLanguage.entries.first().code.toString())
        assertNull(language.value(params("v4.27.4")))
        assertNotNull(language.value(params("v4.28.0")))
    }

    @Test
    fun ungatedPrefsGoToAnyFirmware() {
        assertNull(BoolWatchPref.QuietTimeMotionBacklight.minFirmware)
        assertNotNull(item(BoolWatchPref.QuietTimeMotionBacklight, "1").value(params("v3.12.3")))
    }

    @Test
    fun minimumPrintsAsAVersion() {
        assertEquals("4.41", WatchPrefMinFirmware(4, 41).toString())
        assertEquals("4.41.2", WatchPrefMinFirmware(4, 41, 2).toString())
    }
}
