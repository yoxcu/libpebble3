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
import java.util.concurrent.atomic.AtomicInteger

private val log = Logger.withTag("BluezGattServer")

private const val APP_PATH = "/io/gravel/gatt"
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

actual class GattServer {
    private val conn = DBusConnectionBuilder.forSystemBus().withShared(false).build()
    private val registeredDevices = ConcurrentHashMap<String, SendChannel<ByteArray>>()
    private val _readRequests = MutableSharedFlow<ServerCharacteristicReadRequest>(extraBufferCapacity = 4)
    // True while at least one remote has called StartNotify on the PPoG characteristic.
    // Reset to false when the last device unregisters, so the next connection must wait
    // for a fresh StartNotify before PropertiesChanged notifications are delivered.
    private val _notifySubscribed = MutableStateFlow(false)

    actual val characteristicReadRequest: Flow<ServerCharacteristicReadRequest> =
        _readRequests.asSharedFlow()

    private val sentNotifyCount = AtomicInteger(0)
    private val loopbackCount = AtomicInteger(0)

    actual fun initServer() {
        conn.exportObject(APP_PATH, AppObjectManager())
        conn.exportObject(PPOG_CHAR_PATH, PPoGCharacteristic())
        conn.exportObject(META_CHAR_PATH, MetaCharacteristic())
        // Self-listener: if the D-Bus daemon delivers our own PropertiesChanged back to us,
        // dbus-java IS actually emitting the signal on the bus. If sendData logs "sent" but
        // this never fires, the signal is being silently swallowed in the async executor.
        try {
            conn.addSigHandler(Properties.PropertiesChanged::class.java) { signal ->
                if (signal.getInterfaceName() == "org.bluez.GattCharacteristic1" &&
                    signal.getPropertiesChanged().containsKey("Value")
                ) {
                    val n = loopbackCount.incrementAndGet()
                    log.i { "PropertiesChanged LOOPBACK #$n path=${signal.path} confirmed — signal reached D-Bus system bus (sent=${sentNotifyCount.get()})" }
                }
            }
        } catch (e: Exception) {
            log.w(e) { "Could not register PropertiesChanged self-listener: $e" }
        }
        log.d { "BlueZ GATT objects exported at $APP_PATH" }
    }

    actual suspend fun addServices() {
        try {
            val gattMgr = conn.getRemoteObject("org.bluez", "/org/bluez/hci0", BluezGattManager1::class.java)
            gattMgr.RegisterApplication(DBusPath(APP_PATH), emptyMap())
            log.i { "BlueZ GATT application registered" }
        } catch (e: Exception) {
            log.e(e) { "RegisterApplication failed: $e" }
        }
    }

    actual suspend fun closeServer() {
        try {
            val gattMgr = conn.getRemoteObject("org.bluez", "/org/bluez/hci0", BluezGattManager1::class.java)
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
        val n = sentNotifyCount.incrementAndGet()
        log.d { "sendData: emitting PropertiesChanged #$n (${data.size} bytes: ${data.take(4).joinToString { "%02x".format(it) }}...) notifySubscribed=${_notifySubscribed.value} loopbacksSeen=${loopbackCount.get()}" }
        return try {
            conn.sendMessage(
                Properties.PropertiesChanged(
                    PPOG_CHAR_PATH,
                    "org.bluez.GattCharacteristic1",
                    mapOf("Value" to Variant(data, "ay")),
                    listOf(),
                )
            )
            log.d { "sendData: sendMessage() returned without exception for #$n" }
            SendResult.Success
        } catch (e: Exception) {
            log.e(e) { "sendData failed: $e" }
            SendResult.Failed
        }
    }

    actual fun wasRestoredWithSubscribedCentral(): Boolean = false

    actual suspend fun reAddServices() {
        try {
            val gattMgr = conn.getRemoteObject("org.bluez", "/org/bluez/hci0", BluezGattManager1::class.java)
            try {
                gattMgr.UnregisterApplication(DBusPath(APP_PATH))
                log.i { "reAddServices: BlueZ GATT application unregistered" }
            } catch (e: Exception) {
                log.w(e) { "reAddServices: UnregisterApplication failed (continuing): $e" }
            }
            gattMgr.RegisterApplication(DBusPath(APP_PATH), emptyMap())
            log.i { "reAddServices: BlueZ GATT application re-registered after pairing" }
        } catch (e: Exception) {
            log.e(e) { "reAddServices failed: $e" }
        }
    }

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
