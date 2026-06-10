package io.rebble.libpebblecommon.connection.bt.ble.transport

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private val log = Logger.withTag("BluezGattServer")

private const val APP_PATH = "/io/stoandl/gatt"
private const val SERVICE_PATH = "$APP_PATH/service0"
private const val PPOG_CHAR_PATH = "$SERVICE_PATH/char0"
private const val META_CHAR_PATH = "$SERVICE_PATH/char1"

private const val PPOG_SERVICE_UUID = "10000000-328e-0fbb-c642-1aa6699bdada"
private const val PPOG_CHAR_UUID = "10000001-328e-0fbb-c642-1aa6699bdada"
private const val META_CHAR_UUID = "10000002-328e-0fbb-c642-1aa6699bdada"

@DBusInterfaceName("org.bluez.GattManager1")
interface BluezGattManager1 : DBusInterface {
    fun RegisterApplication(application: DBusPath, options: Map<String, Variant<*>>)
    fun UnregisterApplication(application: DBusPath)
}

@DBusInterfaceName("org.bluez.GattCharacteristic1")
interface BluezGattCharacteristic1 : DBusInterface {
    fun ReadValue(options: Map<String, Variant<*>>): ByteArray
    fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>)
    fun StartNotify()
    fun StopNotify()
}

actual fun openGattServer(
    appContext: AppContext,
    bleConfigFlow: BleConfigFlow,
    libPebbleCoroutineScope: LibPebbleCoroutineScope,
): GattServer? = try {
    GattServer()
} catch (e: Exception) {
    log.e(e) { "Failed to open BlueZ GATT server: $e" }
    null
}

private fun findGattAdapterPath(): String? = try {
    val c = DBusConnectionBuilder.forSystemBus().withShared(false).build()
    try {
        val objMgr = c.getRemoteObject("org.bluez", "/", ObjectManager::class.java)
        @Suppress("UNCHECKED_CAST")
        (objMgr.GetManagedObjects() as Map<DBusPath, Map<String, *>>)
            .entries
            .firstOrNull { (path, ifaces) ->
                Regex("/org/bluez/hci\\d+$").matches(path.toString()) &&
                    "org.bluez.GattManager1" in ifaces
            }
            ?.key?.toString()
    } finally {
        c.disconnect()
    }
} catch (_: Exception) { null }

actual class GattServer {
    // Pin inbound method-call dispatch to a SINGLE thread. The watch's PPoG packets arrive as
    // GattCharacteristic1.WriteValue calls; dbus-java's default multi-threaded dispatch delivers them
    // concurrently and out of order, so the ordered PPoG byte stream gets scrambled ("data out of
    // sequence"). One method-call thread = FIFO wire order. (The only blocking handler, the META
    // ReadValue, is unblocked from a coroutine thread and runs once before any WriteValue traffic.)
    private val conn = DBusConnectionBuilder.forSystemBus()
        .withShared(false)
        .receivingThreadConfig()
        .withMethodCallThreadCount(1)
        .connectionConfig()
        .build()
    private val registeredDevices = ConcurrentHashMap<String, SendChannel<ByteArray>>()
    private val _readRequests = MutableSharedFlow<ServerCharacteristicReadRequest>(extraBufferCapacity = 4)
    // True while at least one remote has called StartNotify on the PPoG characteristic.
    // Reset to false when the last device unregisters, so the next connection must wait
    // for a fresh StartNotify before PropertiesChanged notifications are delivered.
    private val _notifySubscribed = MutableStateFlow(false)

    actual val characteristicReadRequest: Flow<ServerCharacteristicReadRequest> =
        _readRequests.asSharedFlow()

    actual fun initServer() {
        conn.exportObject(APP_PATH, AppObjectManager())
        conn.exportObject(PPOG_CHAR_PATH, PPoGCharacteristic())
        conn.exportObject(META_CHAR_PATH, MetaCharacteristic())
        log.d { "BlueZ GATT objects exported at $APP_PATH" }
    }

    actual suspend fun addServices() {
        val adapterPath = findGattAdapterPath() ?: "/org/bluez/hci0"
        try {
            val gattMgr = conn.getRemoteObject("org.bluez", adapterPath, BluezGattManager1::class.java)
            gattMgr.RegisterApplication(DBusPath(APP_PATH), emptyMap())
            log.i { "BlueZ GATT application registered on $adapterPath" }
        } catch (e: Exception) {
            log.w { "RegisterApplication failed on $adapterPath (Bluetooth not ready?): $e" }
        }
    }

    actual suspend fun closeServer() {
        val adapterPath = findGattAdapterPath() ?: "/org/bluez/hci0"
        try {
            val gattMgr = conn.getRemoteObject("org.bluez", adapterPath, BluezGattManager1::class.java)
            gattMgr.UnregisterApplication(DBusPath(APP_PATH))
        } catch (e: Exception) {
            log.w(e) { "UnregisterApplication failed: $e" }
        }
        conn.disconnect()
    }

    actual fun registerDevice(identifier: PebbleBleIdentifier, sendChannel: SendChannel<ByteArray>) {
        log.i { "registerDevice: ${identifier.asString}" }
        registeredDevices[identifier.asString] = sendChannel
    }

    actual fun unregisterDevice(identifier: PebbleBleIdentifier) {
        log.i { "unregisterDevice: ${identifier.asString}" }
        registeredDevices.remove(identifier.asString)
        if (registeredDevices.isEmpty()) {
            _notifySubscribed.value = false
            log.i { "unregisterDevice: cleared notifySubscribed (no more devices)" }
        }
    }

    actual suspend fun sendData(
        identifier: PebbleBleIdentifier,
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        data: ByteArray,
    ): SendResult {
        if (!_notifySubscribed.value) {
            log.d { "sendData: waiting for StartNotify (not yet subscribed)" }
            val subscribed = withTimeoutOrNull(10_000) { _notifySubscribed.first { it } }
            if (subscribed == null) {
                log.w { "sendData: timed out waiting for StartNotify - dropping notification" }
                return SendResult.Failed
            }
        }
        log.d { "sendData: emitting PropertiesChanged (${data.size} bytes)" }
        return try {
            conn.sendMessage(
                Properties.PropertiesChanged(
                    PPOG_CHAR_PATH,
                    "org.bluez.GattCharacteristic1",
                    mapOf("Value" to Variant(data, "ay")),
                    listOf(),
                )
            )
            SendResult.Success
        } catch (e: Exception) {
            log.e(e) { "sendData failed: $e" }
            SendResult.Failed
        }
    }

    actual fun wasRestoredWithSubscribedCentral(): Boolean = false

    private inner class AppObjectManager : ObjectManager {
        override fun isRemote() = false
        override fun getObjectPath() = APP_PATH

        override fun GetManagedObjects(): Map<DBusPath, Map<String, Map<String, Variant<*>>>> = mapOf(
            DBusPath(SERVICE_PATH) to mapOf(
                "org.bluez.GattService1" to mapOf(
                    "UUID" to Variant(PPOG_SERVICE_UUID),
                    "Primary" to Variant(true),
                )
            ),
            DBusPath(PPOG_CHAR_PATH) to mapOf(
                "org.bluez.GattCharacteristic1" to mapOf(
                    "UUID" to Variant(PPOG_CHAR_UUID),
                    "Service" to Variant(DBusPath(SERVICE_PATH), "o"),
                    "Flags" to Variant(arrayOf("write-without-response", "notify"), "as"),
                )
            ),
            DBusPath(META_CHAR_PATH) to mapOf(
                "org.bluez.GattCharacteristic1" to mapOf(
                    "UUID" to Variant(META_CHAR_UUID),
                    "Service" to Variant(DBusPath(SERVICE_PATH), "o"),
                    "Flags" to Variant(arrayOf("read"), "as"),
                )
            ),
        )
    }

    private inner class PPoGCharacteristic : BluezGattCharacteristic1 {
        override fun isRemote() = false
        override fun getObjectPath() = PPOG_CHAR_PATH

        override fun ReadValue(options: Map<String, Variant<*>>) = ByteArray(0)
        override fun StartNotify() {
            log.i { "StartNotify on PPoG characteristic" }
            _notifySubscribed.value = true
        }
        override fun StopNotify() {
            log.i { "StopNotify on PPoG characteristic" }
            _notifySubscribed.value = false
        }

        override fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>) {
            if (registeredDevices.isEmpty()) {
                log.w { "WriteValue: no registered devices — ${value.size} bytes dropped" }
                return
            }
            // If the watch is writing to us it is connected and ready to receive notifications.
            // Bonded devices may not re-send StartNotify on reconnect (CCCD is preserved in
            // the bond), so unblock sendData() here rather than waiting for an explicit StartNotify.
            if (!_notifySubscribed.value) {
                log.i { "WriteValue received while not subscribed — assuming notifications active" }
                _notifySubscribed.value = true
            }
            log.d { "WriteValue: ${value.size} bytes" }
            registeredDevices.values.forEach { it.trySend(value) }
        }
    }

    private inner class MetaCharacteristic : BluezGattCharacteristic1 {
        override fun isRemote() = false
        override fun getObjectPath() = META_CHAR_PATH

        override fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>) {}
        override fun StartNotify() {}
        override fun StopNotify() {}

        override fun ReadValue(options: Map<String, Variant<*>>): ByteArray {
            val future = CompletableFuture<ByteArray>()
            _readRequests.tryEmit(
                ServerCharacteristicReadRequest(
                    deviceId = PebbleBleIdentifier(""),
                    uuid = Uuid.parse(META_CHAR_UUID),
                    respond = { bytes -> future.complete(bytes); true },
                )
            )
            return try {
                future.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                log.e(e) { "META ReadValue timed out" }
                ByteArray(0)
            }
        }
    }
}
