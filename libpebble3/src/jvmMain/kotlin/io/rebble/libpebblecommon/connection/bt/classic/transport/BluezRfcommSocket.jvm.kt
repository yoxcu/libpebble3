package io.rebble.libpebblecommon.connection.bt.classic.transport

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle

/**
 * A Bluetooth Classic RFCOMM client socket on Linux, via libc/AF_BLUETOOTH (JVM has no native BT
 * socket support). This is the in-process equivalent of `tools/rfcomm_spike.py`, hardware-confirmed
 * against a Pebble Time Steel: a SECURE (authenticated+encrypted) RFCOMM socket dialed out to the
 * watch's SPP server, which reuses the stored BR/EDR LinkKey instead of triggering a fresh pairing.
 *
 * We do NOT use BlueZ's Profile1/ConnectProfile path — on dual-mode Pebbles it returns
 * `br-connection-not-supported`, and it would also require an FD-passing-capable dbus-java transport.
 * A plain secure socket sidesteps both.
 *
 * The six POSIX calls (socket/setsockopt/connect/read/write/close) go through the Foreign Function &
 * Memory API (`java.lang.foreign`, final since JDK 22 — no `--enable-preview`). The downcall stubs are
 * bound against the linker's *default* lookup (the JVM's own symbol table, which has libc statically
 * available), so we never name `libc.so.6` / a soname — that keeps the same fat JAR working on both
 * glibc and musl with no JNA `libjnidispatch.so` native blob to ship. (The restricted-method warning
 * is silenced by launching with `--enable-native-access=ALL-UNNAMED`.)
 */
internal class BluezRfcommSocket private constructor(
    private val fd: Int,
    /**
     * Owns the fd's native lifetime. It currently anchors no segment that outlives a single call (the
     * sockaddr/bt_security structs live only during connect(), and the read/write buffers are scoped to
     * each call's own confined arena below) — its job is to be the connection's lifetime hook, closed in
     * [close] alongside the fd. A SHARED arena (not confined) because [close] can be invoked from a
     * different coroutine/thread than the blocking read/write pumps.
     */
    private val arena: Arena,
) {
    /** Blocking read into [buf]; returns bytes read, 0 on EOF, throws on error. */
    fun read(buf: ByteArray): Int {
        // Per-call confined arena: the off-heap buffer + errno segment are freed deterministically when
        // this call returns (a single fd-lifetime arena would accumulate one segment per read forever),
        // and there is no segment to outlive a concurrent close().
        Arena.ofConfined().use { call ->
            val native = call.allocate(buf.size.toLong())
            val errno = call.allocate(CAPTURE_LAYOUT)
            val n = (READ.invokeExact(errno, fd, native, buf.size.toLong()) as Long).toInt()
            if (n < 0) throw IOException("rfcomm read failed (errno ${errno(errno)})")
            if (n > 0) MemorySegment.copy(native, JAVA_BYTE, 0L, buf, 0, n)
            return n
        }
    }

    /** Blocking write of all of [bytes]. */
    fun write(bytes: ByteArray) {
        Arena.ofConfined().use { call ->
            val native = call.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, native, JAVA_BYTE, 0L, bytes.size)
            val errno = call.allocate(CAPTURE_LAYOUT)
            var off = 0
            while (off < bytes.size) {
                val n = (WRITE.invokeExact(
                    errno, fd, native.asSlice(off.toLong()), (bytes.size - off).toLong(),
                ) as Long).toInt()
                if (n <= 0) throw IOException("rfcomm write failed (errno ${errno(errno)})")
                off += n
            }
        }
    }

    fun close() {
        // close(fd) FIRST: it unblocks any read/write blocked in the kernel (they then see the error and
        // exit their per-call confined arena), so arena.close() can't race an in-flight downcall.
        try { CLOSE.invokeExact(fd) as Int } catch (_: Throwable) {}
        try { arena.close() } catch (_: Throwable) {}
    }

    companion object {
        private val LINKER: Linker = Linker.nativeLinker()
        // Default lookup = the JVM's own symbol table (UNNAMED, libc statically available). We
        // deliberately do NOT libraryLookup("libc.so.6") — naming a glibc soname would break musl.
        private val LOOKUP = LINKER.defaultLookup()

        // Capture `errno` per call (FFM's replacement for JNA's Native.getLastError()). The capture
        // segment is laid out by captureStateLayout(); a VarHandle pulls the "errno" int back out.
        private val CAPTURE_OPT = Linker.Option.captureCallState("errno")
        private val CAPTURE_LAYOUT = Linker.Option.captureStateLayout()
        private val ERRNO_HANDLE: VarHandle = CAPTURE_LAYOUT.varHandle(
            java.lang.foreign.MemoryLayout.PathElement.groupElement("errno"),
        )

        // On the finalized FFM API (JDK 22+) a MemoryLayout VarHandle's coordinates are
        // (MemorySegment, long base-offset), so the 0L offset is required — get(seg) alone throws
        // WrongMethodTypeException at runtime (the capture struct's errno sits at offset 0).
        private fun errno(seg: MemorySegment): Int = ERRNO_HANDLE.get(seg, 0L) as Int

        private fun handle(name: String, desc: FunctionDescriptor, vararg opts: Linker.Option): MethodHandle {
            val addr = LOOKUP.find(name).orElseThrow { UnsatisfiedLinkError("libc symbol not found: $name") }
            return LINKER.downcallHandle(addr, desc, *opts)
        }

        // int socket(int domain, int type, int protocol)
        private val SOCKET = handle(
            "socket", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
        )
        // int setsockopt(int fd, int level, int optname, const void* optval, socklen_t optlen)
        private val SETSOCKOPT = handle(
            "setsockopt", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
        )
        // int connect(int fd, const struct sockaddr* addr, socklen_t addrlen) — errno-captured
        private val CONNECT = handle(
            "connect", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), CAPTURE_OPT,
        )
        // ssize_t read(int fd, void* buf, size_t count) — ssize_t/size_t are JAVA_LONG on 64-bit;
        // errno-captured. Blocking, so NOT isTrivial (must allow the carrier thread to be released).
        private val READ = handle(
            "read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), CAPTURE_OPT,
        )
        // ssize_t write(int fd, const void* buf, size_t count) — errno-captured, blocking.
        private val WRITE = handle(
            "write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), CAPTURE_OPT,
        )
        // int close(int fd) — short, non-blocking → critical(false): the finalized FFM name (JDK 22+)
        // for the old isTrivial option (false = no heap-segment access), for lower downcall overhead.
        private val CLOSE = handle(
            "close", FunctionDescriptor.of(JAVA_INT, JAVA_INT), Linker.Option.critical(false),
        )

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
            val fd = SOCKET.invokeExact(AF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_L2CAP) as Int
            if (fd < 0) return null
            // Short-lived arena scoping every scratch buffer to this query (freed on `use` exit).
            Arena.ofConfined().use { arena ->
                try {
                    // 3s receive timeout so a silent / torn-down SDP server can't hang us
                    // (struct timeval { long tv_sec; long tv_usec; } — 16 bytes on 64-bit).
                    val tv = arena.allocate(16L)  // zero-filled; tv_sec = 3
                    tv.set(JAVA_BYTE, 0L, 3)  // tv_sec low byte = 3
                    SETSOCKOPT.invokeExact(fd, SOL_SOCKET, SO_RCVTIMEO, tv, 16) as Int
                    // struct sockaddr_l2 { family(2); psm(2,LE); bdaddr(6); cid(2); bdaddr_type(1); } = 14
                    val addr = arena.allocate(14L)
                    addr.set(JAVA_BYTE, 0L, (AF_BLUETOOTH and 0xff).toByte())
                    addr.set(JAVA_BYTE, 1L, ((AF_BLUETOOTH shr 8) and 0xff).toByte())
                    addr.set(JAVA_BYTE, 2L, (SDP_PSM and 0xff).toByte())
                    MemorySegment.copy(macToBdaddrLe(mac), 0, addr, JAVA_BYTE, 4L, 6)
                    val errno = arena.allocate(CAPTURE_LAYOUT)
                    if (CONNECT.invokeExact(errno, fd, addr, 14) as Int != 0) return null
                    // SDP_ServiceSearchAttributeRequest: search SerialPort(0x1101), attr range 0x0000-0xFFFF.
                    val reqBytes = byteArrayOf(
                        0x06, 0x00, 0x00, 0x00, 0x0F,
                        0x35, 0x03, 0x19, 0x11, 0x01,
                        0xFF.toByte(), 0xFF.toByte(),
                        0x35, 0x05, 0x0A, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
                        0x00,
                    )
                    val req = arena.allocate(reqBytes.size.toLong())
                    MemorySegment.copy(reqBytes, 0, req, JAVA_BYTE, 0L, reqBytes.size)
                    if ((WRITE.invokeExact(errno, fd, req, reqBytes.size.toLong()) as Long).toInt() <= 0) return null
                    val buf = arena.allocate(4096L)
                    val n = (READ.invokeExact(errno, fd, buf, 4096L) as Long).toInt()
                    if (n <= 4) return null
                    val resp = buf.asSlice(0L, n.toLong()).toArray(JAVA_BYTE)
                    // RFCOMM protocol descriptor: UUID16(0x0003) then uint8 channel → 19 00 03 08 <ch>.
                    for (i in 0..(n - 5)) {
                        if (resp[i] == 0x19.toByte() && resp[i + 1] == 0x00.toByte() &&
                            resp[i + 2] == 0x03.toByte() && resp[i + 3] == 0x08.toByte()
                        ) {
                            return resp[i + 4].toInt() and 0xff
                        }
                    }
                    return null
                } catch (e: Throwable) {
                    return null
                } finally {
                    try { CLOSE.invokeExact(fd) as Int } catch (_: Throwable) {}
                }
            }
        }

        /** Open a secure RFCOMM connection to [mac] on [channel], or throw. */
        fun connect(mac: String, channel: Int): BluezRfcommSocket {
            val fd = SOCKET.invokeExact(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM) as Int
            if (fd < 0) throw IOException("AF_BLUETOOTH socket() failed")
            // This arena lives as long as the socket (closed in close()): it's the fd's lifetime hook,
            // SHARED so close() may run on a different thread than the read/write pumps. Shared because a
            // confined arena can only be closed by its owning thread, which the pumps are not.
            val arena = Arena.ofShared()
            try {
                Arena.ofConfined().use { setup ->
                    // struct bt_security { uint8 level; uint8 key_size; } → ask for auth + encryption,
                    // so the kernel uses the stored LinkKey instead of starting a new pairing.
                    val sec = setup.allocate(2L)
                    sec.set(JAVA_BYTE, 0L, BT_SECURITY_MEDIUM.toByte())
                    sec.set(JAVA_BYTE, 1L, 0)
                    SETSOCKOPT.invokeExact(fd, SOL_BLUETOOTH, BT_SECURITY, sec, 2) as Int  // best-effort

                    // struct sockaddr_rc { sa_family_t rc_family(2); bdaddr_t rc_bdaddr(6); uint8 rc_channel(1); }
                    val addr = setup.allocate(10L)
                    addr.set(JAVA_BYTE, 0L, (AF_BLUETOOTH and 0xff).toByte())
                    addr.set(JAVA_BYTE, 1L, ((AF_BLUETOOTH shr 8) and 0xff).toByte())
                    MemorySegment.copy(macToBdaddrLe(mac), 0, addr, JAVA_BYTE, 2L, 6)
                    addr.set(JAVA_BYTE, 8L, (channel and 0xff).toByte())

                    val errno = setup.allocate(CAPTURE_LAYOUT)
                    if (CONNECT.invokeExact(errno, fd, addr, 10) as Int != 0) {
                        throw IOException("RFCOMM connect to $mac ch$channel failed (errno ${errno(errno)})")
                    }
                }
                return BluezRfcommSocket(fd, arena)
            } catch (e: Throwable) {
                try { CLOSE.invokeExact(fd) as Int } catch (_: Throwable) {}
                try { arena.close() } catch (_: Throwable) {}
                throw e
            }
        }
    }
}
