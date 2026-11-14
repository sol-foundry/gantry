package dev.gantry.dblog.mysql

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/**
 * One logical MySQL packet: the [seq] of its first chunk and its [payload], the chunks of a split payload joined.
 * [length] is the full payload size; when it exceeds the payload, only the first bytes were kept ([cut]).
 */
class MyPacket(
    val seq: Int,
    val payload: ByteArray,
    val length: Long = payload.size.toLong(),
) {
    val cut get() = length > payload.size

    override fun toString() = "MyPacket(seq=$seq, ${payload.size} of $length bytes)"
}

/** Encodes payloads as wire packets. */
object MyPackets {
    /** A payload of this size continues in the next packet. */
    const val MAX_CHUNK = 0xFFFFFF

    /** [payload] as packets starting at [seq], split into [MAX_CHUNK] pieces (with an empty tail when it divides evenly). */
    fun encode(
        seq: Int,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 4 * (payload.size / MAX_CHUNK + 1))
        var at = 0
        var s = seq
        while (true) {
            val n = minOf(MAX_CHUNK, payload.size - at)
            out.write(n and 0xFF)
            out.write(n shr 8 and 0xFF)
            out.write(n shr 16 and 0xFF)
            out.write(s and 0xFF)
            out.write(payload, at, n)
            at += n
            s++
            if (n < MAX_CHUNK) return out.toByteArray()
        }
    }
}

/**
 * Splits one direction of a connection into [MyPacket]s, whatever the read boundaries, joining payloads split into
 * 16 MB chunks. A payload past [maxPayload] keeps its first [maxPayload] bytes; the rest is skipped.
 */
class MyPacketReader(
    private val maxPayload: Int = 16 * 1024 * 1024,
) {
    private val header = ByteArray(4)
    private var headerFill = 0
    private var chunkLeft = -1
    private var continued = false
    private var seq = -1
    private var total = 0L
    private var buffer = ByteArray(256)
    private var fill = 0

    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): List<MyPacket> {
        val out = ArrayList<MyPacket>()
        var i = offset
        val end = offset + length
        while (i < end) {
            if (chunkLeft < 0) {
                header[headerFill++] = bytes[i++]
                if (headerFill < 4) continue
                headerFill = 0
                val size = (header[0].toInt() and 0xFF) or (header[1].toInt() and 0xFF shl 8) or (header[2].toInt() and 0xFF shl 16)
                if (seq < 0) seq = header[3].toInt() and 0xFF
                chunkLeft = size
                continued = size == MyPackets.MAX_CHUNK
                total += size
                if (size == 0) endChunk()?.let { out += it }
            } else {
                val n = minOf(chunkLeft, end - i)
                val keep = minOf(n, maxPayload - fill)
                if (keep > 0) {
                    ensure(fill + keep)
                    System.arraycopy(bytes, i, buffer, fill, keep)
                    fill += keep
                }
                chunkLeft -= n
                i += n
                if (chunkLeft == 0) endChunk()?.let { out += it }
            }
        }
        return out
    }

    private fun ensure(size: Int) {
        if (size <= buffer.size) return
        buffer = buffer.copyOf(minOf(maxPayload.toLong(), maxOf(size.toLong(), buffer.size * 2L)).toInt())
    }

    private fun endChunk(): MyPacket? {
        chunkLeft = -1
        if (continued) return null
        val packet = MyPacket(seq, buffer.copyOf(fill), total)
        seq = -1
        total = 0
        fill = 0
        if (buffer.size > 64 * 1024) buffer = ByteArray(256)
        return packet
    }
}

/** Builds a payload in wire order (little-endian integers). */
class MyPayload {
    private val out = ByteArrayOutputStream()

    fun byte(v: Int) = apply { out.write(v) }

    fun int16(v: Int) = byte(v and 0xFF).byte(v shr 8 and 0xFF)

    fun int24(v: Int) = int16(v).byte(v shr 16 and 0xFF)

    fun int32(v: Int) = int16(v).int16(v shr 16)

    fun int64(v: Long) = int32(v.toInt()).int32((v shr 32).toInt())

    fun lenenc(v: Long) =
        when {
            v < 0xFB -> byte(v.toInt())
            v <= 0xFFFF -> byte(0xFC).int16(v.toInt())
            v <= 0xFFFFFF -> byte(0xFD).int24(v.toInt())
            else -> byte(0xFE).int64(v)
        }

    fun lenencBytes(b: ByteArray) = lenenc(b.size.toLong()).bytes(b)

    fun lenencString(s: String) = lenencBytes(s.toByteArray(UTF_8))

    fun cstring(s: String) = string(s).byte(0)

    fun string(s: String) = bytes(s.toByteArray(UTF_8))

    fun bytes(b: ByteArray) = apply { out.write(b) }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Reads a payload; throws on underflow, which callers catch. */
internal class MyReader(
    private val b: ByteArray,
) {
    var pos = 0
        private set

    val remaining get() = b.size - pos

    fun byte(): Int {
        if (pos >= b.size) throw IndexOutOfBoundsException("payload ended")
        return b[pos++].toInt() and 0xFF
    }

    fun peek(): Int = if (pos < b.size) b[pos].toInt() and 0xFF else -1

    fun int16(): Int = byte() or (byte() shl 8)

    fun int24(): Int = int16() or (byte() shl 16)

    fun int32(): Int = int16() or (int16() shl 16)

    fun int64(): Long = (int32().toLong() and 0xFFFFFFFFL) or (int32().toLong() shl 32)

    /** A length-encoded integer, or -1 for the NULL marker (0xFB). */
    fun lenenc(): Long =
        when (val first = byte()) {
            0xFB -> -1
            0xFC -> int16().toLong()
            0xFD -> int24().toLong()
            0xFE -> int64()
            0xFF -> throw IllegalStateException("not a length")
            else -> first.toLong()
        }

    fun lenencString(): String = String(lenencSlice(Int.MAX_VALUE), UTF_8)

    fun lenencSlice(keep: Int): ByteArray {
        val n = lenenc()
        if (n < 0) throw IllegalStateException("unexpected NULL")
        return slice(n, keep)
    }

    fun cstring(): String {
        val start = pos
        while (pos < b.size && b[pos] != 0.toByte()) pos++
        if (pos >= b.size) throw IndexOutOfBoundsException("unterminated string")
        return String(b, start, pos++ - start, UTF_8)
    }

    fun bytes(n: Int): ByteArray = slice(n.toLong(), n)

    fun skip(n: Long) {
        if (n < 0 || n > remaining) throw IndexOutOfBoundsException("payload ended")
        pos += n.toInt()
    }

    /** Reads an [n]-byte value but copies only its first [keep] bytes. */
    fun slice(
        n: Long,
        keep: Int,
    ): ByteArray {
        if (n < 0 || n > remaining) throw IndexOutOfBoundsException("payload ended")
        val start = pos
        pos += n.toInt()
        return b.copyOfRange(start, start + minOf(n, keep.toLong()).toInt())
    }

    /** The rest of the payload as UTF-8. */
    fun rest(): String = String(b, pos, remaining, UTF_8).also { pos = b.size }
}

/** Capability flags the proxy and session read. */
internal object Caps {
    const val CONNECT_WITH_DB = 0x8L
    const val COMPRESS = 0x20L
    const val PROTOCOL_41 = 0x200L
    const val SSL = 0x800L
    const val SECURE_CONNECTION = 0x8000L
    const val MULTI_STATEMENTS = 0x10000L
    const val PLUGIN_AUTH = 0x80000L
    const val CONNECT_ATTRS = 0x100000L
    const val LENENC_DATA = 0x200000L
    const val DEPRECATE_EOF = 0x1000000L
    const val OPTIONAL_METADATA = 0x2000000L
    const val ZSTD = 0x4000000L
    const val QUERY_ATTRIBUTES = 0x8000000L
}

/** The connection-phase packets the proxy inspects before it starts relaying. */
object MyHandshake {
    private const val SSL_REQUEST_SIZE = 32

    /**
     * The server greeting as the client should see it: TLS advertised only when [tls] and the server offers it, and
     * compression and optional result metadata withdrawn, since the session could not read either. Same length.
     */
    fun rewriteGreeting(
        payload: ByteArray,
        tls: Boolean,
    ): ByteArray {
        val at = capabilityOffset(payload) ?: return payload
        val out = payload.copyOf()
        val caps = greetingCapabilities(payload)
        var rewritten = caps and (Caps.COMPRESS or Caps.ZSTD or Caps.OPTIONAL_METADATA).inv()
        rewritten = if (tls && caps and Caps.SSL != 0L) rewritten or Caps.SSL else rewritten and Caps.SSL.inv()
        out[at] = rewritten.toByte()
        out[at + 1] = (rewritten shr 8).toByte()
        if (at + 6 < out.size) {
            out[at + 5] = (rewritten shr 16).toByte()
            out[at + 6] = (rewritten shr 24).toByte()
        }
        return out
    }

    /** The capability flags of a HandshakeV10 greeting, or 0 when it is not one. */
    fun greetingCapabilities(payload: ByteArray): Long {
        val at = capabilityOffset(payload) ?: return 0
        val low = (payload[at].toLong() and 0xFF) or (payload[at + 1].toLong() and 0xFF shl 8)
        if (at + 6 >= payload.size) return low
        return low or (payload[at + 5].toLong() and 0xFF shl 16) or (payload[at + 6].toLong() and 0xFF shl 24)
    }

    /** True for the short HandshakeResponse prefix a client sends to ask for TLS. */
    fun isSslRequest(payload: ByteArray): Boolean =
        payload.size == SSL_REQUEST_SIZE && MyReader(payload).int32().toLong() and Caps.SSL != 0L

    // protocol version 10, server version, connection id (4), auth data (8), filler (1), then the low capability bytes.
    private fun capabilityOffset(payload: ByteArray): Int? {
        if (payload.isEmpty() || payload[0].toInt() != 10) return null
        var i = 1
        while (i < payload.size && payload[i] != 0.toByte()) i++
        val at = i + 1 + 4 + 8 + 1
        return if (at + 1 < payload.size) at else null
    }
}
