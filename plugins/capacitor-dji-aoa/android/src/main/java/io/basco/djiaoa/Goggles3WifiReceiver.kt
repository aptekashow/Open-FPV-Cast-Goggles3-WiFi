package io.basco.djiaoa

import android.net.Network
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Direct DJI Goggles 3 Wi-Fi Liveview receiver.
 *
 * The session, UDP framing, receive windows and H.264 fragment layout are an Android/Kotlin
 * adaptation of the independently reverse-engineered DJI Goggles Bridge protocol by Evols
 * (CC BY 4.0): https://github.com/Evols/dji-goggles-bridge
 * See NOTICE-DJI-GOGGLES-BRIDGE.md for attribution.
 *
 * The phone must already be connected to the Wi-Fi network broadcast by Goggles 3. This class
 * does not pair over Bluetooth or configure the goggles' SSID/password. It binds the UDP socket
 * to Android's active Wi-Fi Network and receives the raw H.264 Annex-B stream.
 *
 * [INFERRED] Network addresses, ports, handshake bytes and packet layouts are ported from the
 * upstream reverse-engineered protocol; they have not yet been validated on this Android build.
 */
class Goggles3WifiReceiver(
    private val network: Network,
    private val onVideo: (ByteArray) -> Unit,
    private val onConnected: () -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var socket: DatagramSocket? = null

    private val session = Random.nextInt(0x10000)
    private val seed = Random.nextInt(0x2000) shl 3

    fun start() {
        if (running) return
        running = true
        worker = Thread(::receiveLoop, "Goggles3-WiFi").also { it.isDaemon = true; it.start() }
    }

    fun close() {
        running = false
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        worker?.interrupt()
        worker = null
    }

    private fun receiveLoop() {
        val videoWindow = ReceiveWindow(seed)
        val controlWindow = ReceiveWindow(seed)
        val reassembler = VideoReassembler()
        var connected = false
        var startedAt = SystemClock.elapsedRealtime()
        var lastHandshake = 0L
        var lastAck = 0L
        var lastReceived = startedAt

        try {
            val sock = DatagramSocket(null)
            socket = sock
            sock.reuseAddress = false
            sock.receiveBufferSize = 4 * 1024 * 1024
            network.bindSocket(sock)
            try {
                sock.bind(InetSocketAddress(LOCAL_IP, LOCAL_PORT))
            } catch (e: java.net.BindException) {
                throw IllegalStateException(
                    "The Goggles 3 Wi-Fi network must assign this phone 192.168.60.1. Reconnect to the goggles Wi-Fi and try again.", e
                )
            }
            sock.connect(InetSocketAddress(HEADSET_IP, HEADSET_PORT))
            sock.soTimeout = 5

            send(sock, buildHandshake())
            lastHandshake = SystemClock.elapsedRealtime()
            val buffer = ByteArray(65535)

            while (running) {
                val now = SystemClock.elapsedRealtime()
                reassembler.expire(now)

                if (!connected) {
                    if (now - startedAt > HANDSHAKE_TIMEOUT_MS) {
                        throw IllegalStateException(
                            "No reply from Goggles 3. Check that the phone is connected to the goggles' Wi-Fi and Share Liveview is enabled."
                        )
                    }
                    if (now - lastHandshake >= 1000L) {
                        send(sock, buildHandshake())
                        lastHandshake = now
                    }
                } else {
                    if (now - lastReceived > IDLE_TIMEOUT_MS) {
                        throw IllegalStateException("Goggles 3 stopped sending video packets")
                    }
                    if (now - lastAck >= ACK_INTERVAL_MS) {
                        send(sock, buildAck(6, videoWindow, controlWindow))
                        lastAck = now
                    }
                }

                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    packet.length = buffer.size
                    sock.receive(packet)
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    val parsed = parsePacket(data) ?: continue
                    val receivedAt = SystemClock.elapsedRealtime()
                    lastReceived = receivedAt

                    if (!connected) {
                        connected = true
                        Log.i(TAG, "Goggles 3 Wi-Fi session established")
                        onConnected()
                    }

                    when (parsed.kind) {
                        0 -> Unit // Handshake reply.
                        1 -> {
                            if (parsed.payload.size >= 10) {
                                videoWindow.advanceSender(u16(parsed.payload, 0))
                                controlWindow.advanceSender(u16(parsed.payload, 8))
                            }
                        }
                        2 -> {
                            val fragment = parseVideo(parsed) ?: continue
                            videoWindow.observe(fragment.sequence, fragment.senderStart)
                            val unit = reassembler.add(fragment, receivedAt)
                            if (fragment.part == 0 || fragment.part == fragment.count - 1) {
                                val ackType = if (fragment.part == fragment.count - 1) 4 else 6
                                send(sock, buildAck(ackType, videoWindow, controlWindow))
                                lastAck = receivedAt
                            }
                            if (unit != null) onVideo(unit)
                        }
                        3 -> {
                            if (parsed.payload.size >= 2) {
                                controlWindow.observe(parsed.sequence, u16(parsed.payload, 0))
                                send(sock, buildAck(6, videoWindow, controlWindow))
                                lastAck = receivedAt
                            }
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Keep the ACK cadence independent of incoming video packet timing.
                }
            }
        } catch (t: Throwable) {
            if (running) {
                Log.e(TAG, "Wi-Fi receiver stopped", t)
                onError(t.message ?: t.javaClass.simpleName)
            }
        } finally {
            running = false
            try { socket?.close() } catch (_: Throwable) {}
            socket = null
        }
    }

    private fun send(sock: DatagramSocket, bytes: ByteArray) {
        sock.send(DatagramPacket(bytes, bytes.size))
    }

    private fun buildHandshake(): ByteArray {
        val payload = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(seed.toShort()).put(HANDSHAKE_PARAMETERS).array()
        return buildPacket(0, 0, payload)
    }

    private fun buildAck(kind: Int, video: ReceiveWindow, control: ReceiveWindow): ByteArray {
        val payload = ByteArrayOutputStreamCompat()
        payload.write(video.encode())
        payload.write(control.encode())
        val noCommands = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(seed.toShort()).putShort(seed.toShort())
            .putShort(0).putShort(0).putShort(0).array()
        payload.write(noCommands)
        return buildPacket(0, kind, payload.toByteArray())
    }

    private fun buildPacket(sequence: Int, kind: Int, payload: ByteArray): ByteArray {
        val length = 8 + payload.size
        require(length <= 0x7FFF)
        val out = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort((length or 0x8000).toShort())
        out.putShort(session.toShort())
        out.putShort(sequence.toShort())
        out.put(kind.toByte())
        val header = out.array()
        var checksum = 0
        for (i in 0..6) checksum = checksum xor (header[i].toInt() and 0xFF)
        out.put(checksum.toByte())
        out.put(payload)
        return out.array()
    }

    private fun parsePacket(data: ByteArray): Packet? {
        if (data.size < 8) return null
        val length = u16(data, 0)
        if ((length and 0x8000) == 0 || (length and 0x7FFF) != data.size) return null
        if (u16(data, 2) != session) return null
        var checksum = 0
        for (i in 0..6) checksum = checksum xor (data[i].toInt() and 0xFF)
        if (checksum != (data[7].toInt() and 0xFF)) return null
        val kind = data[6].toInt() and 0xFF
        if (kind !in 0..3) return null
        val payload = data.copyOfRange(8, data.size)
        if (kind == 1 && payload.size < 16) return null
        if (kind in 2..3 && payload.size < 12) return null
        return Packet(u16(data, 4), kind, payload)
    }

    private fun parseVideo(packet: Packet): VideoFragment? {
        val p = packet.payload
        if (packet.kind != 2 || p.size < 12) return null
        val count = p[9].toInt() and 0x7F
        val part = ((p[10].toInt() and 0x1F) shl 1) or ((p[9].toInt() ushr 7) and 1)
        if (count !in 1..64 || part !in 0 until count) return null
        return VideoFragment(
            sequence = packet.sequence,
            senderStart = u16(p, 0),
            frameId = p[8].toInt() and 0xFF,
            part = part,
            count = count,
            data = p.copyOfRange(12, p.size)
        )
    }

    private data class Packet(val sequence: Int, val kind: Int, val payload: ByteArray)
    private data class VideoFragment(
        val sequence: Int, val senderStart: Int, val frameId: Int,
        val part: Int, val count: Int, val data: ByteArray
    ) {
        val baseSequence: Int get() = ((sequence and SEQUENCE_MASK) - part * SEQUENCE_STEP) and 0xFFFF
    }

    private class ReceiveWindow(seed: Int) {
        private var start = seed and SEQUENCE_MASK
        private var end = start
        private val received = mutableSetOf<Int>()

        fun advanceSender(senderStart: Int) {
            val next = senderStart and SEQUENCE_MASK
            if (ahead(next, start)) {
                start = next
                received.removeAll { !ahead(it, start) }
                if (ahead(start, end)) end = start
                advanceContiguous()
            }
        }

        fun observe(sequence: Int, senderStart: Int) {
            advanceSender(senderStart)
            val seq = sequence and SEQUENCE_MASK
            if (!ahead(seq, start)) return
            received.add(seq)
            if (ahead(seq, end)) end = seq
            advanceContiguous()
        }

        private fun advanceContiguous() {
            while (true) {
                val next = (start + SEQUENCE_STEP) and 0xFFFF
                if (!received.remove(next)) break
                start = next
            }
        }

        fun encode(): ByteArray {
            val missing = ArrayList<Int>()
            var step = SEQUENCE_STEP
            while (step <= distance(end, start) && missing.size < 128) {
                val seq = (start + step) and 0xFFFF
                if (seq !in received) missing.add(seq)
                step += SEQUENCE_STEP
            }
            val out = ByteBuffer.allocate(6 + missing.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            out.putShort(start.toShort()).putShort(end.toShort()).putShort(missing.size.toShort())
            missing.forEach { out.putShort(it.toShort()) }
            return out.array()
        }
    }

    private data class PartialUnit(
        val frameId: Int,
        val count: Int,
        val createdAt: Long,
        val parts: MutableMap<Int, ByteArray> = mutableMapOf()
    )

    private class VideoReassembler {
        private val pending = mutableMapOf<Int, PartialUnit>()
        private var lastEmittedEnd: Int? = null

        fun expire(now: Long) {
            val expired = pending.filterValues { now - it.createdAt >= REASSEMBLY_EXPIRY_MS }.keys
            expired.forEach { pending.remove(it) }
        }

        fun add(fragment: VideoFragment, now: Long): ByteArray? {
            expire(now)
            val base = fragment.baseSequence
            val last = lastEmittedEnd
            if (last != null && !ahead(base, last)) return null
            val unit = pending.getOrPut(base) { PartialUnit(fragment.frameId, fragment.count, now) }
            if (unit.frameId != fragment.frameId || unit.count != fragment.count) {
                pending.remove(base)
                return null
            }
            unit.parts.putIfAbsent(fragment.part, fragment.data)
            if (unit.parts.size != unit.count) {
                if (pending.size > MAX_PENDING_UNITS) {
                    val oldest = pending.minByOrNull { it.value.createdAt }?.key
                    if (oldest != null) pending.remove(oldest)
                }
                return null
            }
            val total = (0 until unit.count).sumOf { unit.parts[it]?.size ?: 0 }
            val out = ByteArray(total)
            var offset = 0
            for (i in 0 until unit.count) {
                val part = unit.parts[i] ?: return null
                System.arraycopy(part, 0, out, offset, part.size)
                offset += part.size
            }
            pending.remove(base)
            lastEmittedEnd = (base + (unit.count - 1) * SEQUENCE_STEP) and 0xFFFF
            val stale = pending.keys.filter { !ahead(it, lastEmittedEnd!!) }
            stale.forEach { pending.remove(it) }
            return out
        }
    }

    private class ByteArrayOutputStreamCompat {
        private val chunks = ArrayList<ByteArray>()
        private var size = 0
        fun write(bytes: ByteArray) { chunks.add(bytes); size += bytes.size }
        fun toByteArray(): ByteArray {
            val out = ByteArray(size)
            var offset = 0
            chunks.forEach { System.arraycopy(it, 0, out, offset, it.size); offset += it.size }
            return out
        }
    }

    companion object {
        private const val TAG = "Goggles3WiFi"
        private const val LOCAL_IP = "192.168.60.1"
        private const val HEADSET_IP = "192.168.60.2"
        private const val HEADSET_PORT = 9003
        private const val LOCAL_PORT = 12346
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val IDLE_TIMEOUT_MS = 5_000L
        private const val ACK_INTERVAL_MS = 16L
        private const val SEQUENCE_MASK = 0xFFF8
        private const val SEQUENCE_STEP = 8
        private const val REASSEMBLY_EXPIRY_MS = 500L
        private const val MAX_PENDING_UNITS = 32
        private val HANDSHAKE_PARAMETERS = byteArrayOf(
            0x64, 0x00, 0x64, 0x00, 0xC0.toByte(), 0x05, 0x14, 0x00,
            0x00, 0x64, 0x00, 0x00, 0x01, 0x90.toByte(), 0x01, 0xC0.toByte(),
            0x05, 0x14, 0x00, 0x00, 0x64, 0x00, 0x14, 0x00,
            0x64, 0x00, 0xC0.toByte(), 0x05, 0x14, 0x00, 0x00, 0x64,
            0x00, 0x01, 0x01, 0x04, 0x01, 0x02
        )

        private fun u16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun distance(new: Int, old: Int): Int = (new - old) and 0xFFFF
        private fun ahead(new: Int, old: Int): Boolean = distance(new, old) in 1 until 0x8000
    }
}
