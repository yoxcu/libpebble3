package io.rebble.libpebblecommon.datalogging

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.SystemAppIDs.SYSTEM_APP_UUID
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.packets.DataItemType
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.structmapper.SBytes
import io.rebble.libpebblecommon.structmapper.SUInt
import io.rebble.libpebblecommon.structmapper.StructMappable
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.uuid.Uuid

/**
 * One SendDataItems frame from a *custom watchapp* datalog session — i.e. not a health tag nor a
 * system-app (Memfault/analytics) session, which are consumed internally. This is the PebbleKit
 * "DataLogging" surface: the phone-side host decides what to do with it. [data] is the raw frame
 * payload; split it into [itemSize]-byte items and decode each per [itemType].
 */
data class DataLogRecord(
    val sessionId: UByte,
    val uuid: Uuid,
    val tag: UInt,
    /** Session-open timestamp reported by the watch (unix seconds). */
    val timestamp: UInt,
    val itemType: DataItemType,
    val itemSize: UShort,
    val data: ByteArray,
    val itemsLeft: UInt,
)

class Datalogging(
    private val webServices: WebServices,
    private val healthDataProcessor: HealthDataProcessor,
) {
    private val logger = Logger.withTag("Datalogging")

    private val _records = MutableSharedFlow<DataLogRecord>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Datalog frames from custom watchapps (non-health, non-system). Inert until a host subscribes;
     * libpebble3 itself does nothing with these. stoandl persists them (see DatalogStore).
     */
    val records: SharedFlow<DataLogRecord> = _records.asSharedFlow()

    fun logData(
        sessionId: UByte,
        uuid: Uuid,
        tag: UInt,
        data: ByteArray,
        watchInfo: WatchInfo,
        itemSize: UShort,
        itemsLeft: UInt,
        timestamp: UInt,
        itemType: DataItemType,
    ) {
        // Handle health tags
        if (tag in HealthDataProcessor.HEALTH_TAGS) {
            healthDataProcessor.handleSendDataItems(sessionId, data, itemsLeft)
            return
        }

        // Handle system-app datalogging tags
        if (uuid == SYSTEM_APP_UUID) {
            when (tag) {
                MEMFAULT_CHUNKS_TAG -> {
                    // A single SendDataItems payload can contain multiple items,
                    // each itemSize bytes. Parse each one as a MemfaultChunk.
                    val size = itemSize.toInt()
                    var offset = 0
                    while (offset + size <= data.size) {
                        val itemData = data.copyOfRange(offset, offset + size)
                        val chunk = MemfaultChunk()
                        chunk.fromBytes(DataBuffer(itemData.toUByteArray()))
                        webServices.uploadMemfaultChunk(chunk.bytes.get().toByteArray(), watchInfo)
                        offset += size
                    }
                }
                ANALYTICS_HEARTBEAT_TAG -> {
                    // Fixed-size native_heartbeat_record items (no inner length prefix).
                    val size = itemSize.toInt()
                    if (size <= 0) {
                        logger.w { "Analytics heartbeat with itemSize=$size; ignoring" }
                        return
                    }
                    var offset = 0
                    while (offset + size <= data.size) {
                        val itemData = data.copyOfRange(offset, offset + size)
                        webServices.uploadAnalyticsHeartbeat(itemData, watchInfo)
                        offset += size
                    }
                }
            }
            return
        }

        // Custom watchapp data: hand it to whatever host subscribed to `records` (no-op if none).
        _records.tryEmit(
            DataLogRecord(sessionId, uuid, tag, timestamp, itemType, itemSize, data, itemsLeft)
        )
    }

    fun openSession(sessionId: UByte, tag: UInt, applicationUuid: Uuid, itemSize: UShort) {
        if (tag in HealthDataProcessor.HEALTH_TAGS) {
            healthDataProcessor.handleSessionOpen(sessionId, tag, applicationUuid, itemSize)
        }
    }

    fun closeSession(sessionId: UByte, tag: UInt) {
        if (tag in HealthDataProcessor.HEALTH_TAGS) {
            healthDataProcessor.handleSessionClose(sessionId)
        }
    }

    companion object {
        private val MEMFAULT_CHUNKS_TAG: UInt = 86u
        private val ANALYTICS_HEARTBEAT_TAG: UInt = 87u
    }
}

class MemfaultChunk : StructMappable() {
    val chunkSize: SUInt = SUInt(m, 0u, Endian.Little)
    val bytes: SBytes = SBytes(m).apply { linkWithSize(chunkSize) }
}
