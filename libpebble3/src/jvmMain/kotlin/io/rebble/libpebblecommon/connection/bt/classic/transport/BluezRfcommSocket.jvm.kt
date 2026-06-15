package io.rebble.libpebblecommon.connection.bt.classic.transport

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import java.io.IOException

/**
 * A Bluetooth Classic RFCOMM client socket on Linux, via libc/AF_BLUETOOTH (JVM has no native BT
 * socket support). This is the in-process equivalent of `tools/rfcomm_spike.py`, hardware-confirmed
 * against a Pebble Time Steel: a SECURE (authenticated+encrypted) RFCOMM socket dialed out to the
 * watch's SPP server, which reuses the stored BR/EDR LinkKey instead of triggering a fresh pairing.
 *
 * We do NOT use BlueZ's Profile1/ConnectProfile path — on dual-mode Pebbles it returns
 * `br-connection-not-supported`, and it would also require an FD-passing-capable dbus-java transport.
 * A plain secure socket sidesteps both.
 */
internal class BluezRfcommSocket private constructor(private val fd: Int) {
    /** Blocking read into [buf]; returns bytes read, 0 on EOF, throws on error. */
    fun read(buf: ByteArray): Int {
        val n = C.read(fd, buf, NativeLong(buf.size.toLong())).toInt()
        if (n < 0) throw IOException("rfcomm read failed (errno ${Native.getLastError()})")
        return n
    }

    /** Blocking write of all of [bytes]. */
    fun write(bytes: ByteArray) {
        var off = 0
        while (off < bytes.size) {
            val slice = if (off == 0) bytes else bytes.copyOfRange(off, bytes.size)
            val n = C.write(fd, slice, NativeLong((bytes.size - off).toLong())).toInt()
            if (n <= 0) throw IOException("rfcomm write failed (errno ${Native.getLastError()})")
            off += n
        }
    }

    fun close() {
        try { C.close(fd) } catch (_: Throwable) {}
    }

    private interface CLib : Library {
        fun socket(domain: Int, type: Int, protocol: Int): Int
        fun setsockopt(fd: Int, level: Int, optname: Int, optval: ByteArray, optlen: Int): Int
        fun connect(fd: Int, addr: ByteArray, addrlen: Int): Int
        fun read(fd: Int, buf: ByteArray, count: NativeLong): NativeLong
        fun write(fd: Int, buf: ByteArray, count: NativeLong): NativeLong
        fun close(fd: Int): Int
    }

    companion object {
        private val C: CLib = Native.load("c", CLib::class.java)

        // <bluetooth/bluetooth.h> / <sys/socket.h>
        private const val AF_BLUETOOTH = 31
        private const val SOCK_STREAM = 1
        private const val BTPROTO_RFCOMM = 3
        private const val SOL_BLUETOOTH = 274
        private const val BT_SECURITY = 4
        private const val BT_SECURITY_MEDIUM = 2
        private const val BTPROTO_L2CAP = 0
        private const val SOCK_SEQPACKET = 5
        private const val SOL_SOCKET = 1
        private const val SO_RCVTIMEO = 20
        private const val SDP_PSM = 1

        /** "B0:B4:48:B6:1E:81" → bdaddr_t (6 bytes, little-endian, i.e. reversed). */
        private fun macToBdaddrLe(mac: String): ByteArray {
            val parts = mac.trim().split(":")
            require(parts.size == 6) { "bad MAC: $mac" }
            val be = ByteArray(6) { parts[it].toInt(16).toByte() }
            return ByteArray(6) { be[5 - it] }  // reverse → little-endian bdaddr
        }

        /**
         * Resolve the watch's Serial Port (0x1101) RFCOMM channel via a native SDP query over L2CAP
         * (PSM 1) — no `sdptool` dependency. Best-effort: null on any failure (caller falls back to the
         * configured channel). Connects SDP, sends a ServiceSearchAttributeRequest for SerialPort, and
         * pulls the RFCOMM channel out of the response (UUID16 0x0003 followed by a uint8 channel).
         */
        fun resolveSppChannel(mac: String): Int? {
            val fd = C.socket(AF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_L2CAP)
            if (fd < 0) return null
            try {
                // 3s receive timeout so a silent / torn-down SDP server can't hang us
                // (struct timeval { long tv_sec; long tv_usec; } — 16 bytes on 64-bit).
                val tv = ByteArray(16).also { it[0] = 3 }  // tv_sec = 3
                C.setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv, tv.size)
                // struct sockaddr_l2 { family(2); psm(2,LE); bdaddr(6); cid(2); bdaddr_type(1); } = 14
                val addr = ByteArray(14)
                addr[0] = (AF_BLUETOOTH and 0xff).toByte()
                addr[1] = ((AF_BLUETOOTH shr 8) and 0xff).toByte()
                addr[2] = (SDP_PSM and 0xff).toByte()
                macToBdaddrLe(mac).copyInto(addr, destinationOffset = 4)
                if (C.connect(fd, addr, addr.size) != 0) return null
                // SDP_ServiceSearchAttributeRequest: search SerialPort(0x1101), attr range 0x0000-0xFFFF.
                val req = byteArrayOf(
                    0x06, 0x00, 0x00, 0x00, 0x0F,
                    0x35, 0x03, 0x19, 0x11, 0x01,
                    0xFF.toByte(), 0xFF.toByte(),
                    0x35, 0x05, 0x0A, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
                    0x00,
                )
                if (C.write(fd, req, NativeLong(req.size.toLong())).toInt() <= 0) return null
                val buf = ByteArray(4096)
                val n = C.read(fd, buf, NativeLong(buf.size.toLong())).toInt()
                if (n <= 4) return null
                // RFCOMM protocol descriptor: UUID16(0x0003) then uint8 channel → 19 00 03 08 <ch>.
                for (i in 0..(n - 5)) {
                    if (buf[i] == 0x19.toByte() && buf[i + 1] == 0x00.toByte() &&
                        buf[i + 2] == 0x03.toByte() && buf[i + 3] == 0x08.toByte()
                    ) {
                        return buf[i + 4].toInt() and 0xff
                    }
                }
                return null
            } catch (e: Throwable) {
                return null
            } finally {
                try { C.close(fd) } catch (_: Throwable) {}
            }
        }

        /** Open a secure RFCOMM connection to [mac] on [channel], or throw. */
        fun connect(mac: String, channel: Int): BluezRfcommSocket {
            val fd = C.socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM)
            if (fd < 0) throw IOException("AF_BLUETOOTH socket() failed (errno ${Native.getLastError()})")
            try {
                // struct bt_security { uint8 level; uint8 key_size; } → ask for auth + encryption,
                // so the kernel uses the stored LinkKey instead of starting a new pairing.
                val sec = byteArrayOf(BT_SECURITY_MEDIUM.toByte(), 0)
                C.setsockopt(fd, SOL_BLUETOOTH, BT_SECURITY, sec, sec.size)  // best-effort

                // struct sockaddr_rc { sa_family_t rc_family(2); bdaddr_t rc_bdaddr(6); uint8 rc_channel(1); }
                val addr = ByteArray(10)
                addr[0] = (AF_BLUETOOTH and 0xff).toByte()
                addr[1] = ((AF_BLUETOOTH shr 8) and 0xff).toByte()
                macToBdaddrLe(mac).copyInto(addr, destinationOffset = 2)
                addr[8] = (channel and 0xff).toByte()

                if (C.connect(fd, addr, addr.size) != 0) {
                    throw IOException("RFCOMM connect to $mac ch$channel failed (errno ${Native.getLastError()})")
                }
                return BluezRfcommSocket(fd)
            } catch (e: Throwable) {
                try { C.close(fd) } catch (_: Throwable) {}
                throw e
            }
        }
    }
}
