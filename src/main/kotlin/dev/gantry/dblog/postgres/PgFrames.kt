package dev.gantry.dblog.postgres

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/** Type of the untyped first messages a client sends: StartupMessage, SSLRequest, GSSENCRequest, CancelRequest. */
const val PG_UNTYPED = '\u0000'

private const val SSL_REQUEST = 80877103
private const val GSSENC_REQUEST = 80877104

/**
 * One Postgres wire message: [type] and the bytes after the length. [cut] is true when the frame was larger than the
 * reader's cap and only its first bytes were kept.
 */
class PgMessage(
    val type: Char,
    val payload: ByteArray,
    val cut: Boolean = false,
) {
    override fun toString() = "PgMessage('$type', ${payload.size} bytes${if (cut) ", cut" else ""})"
}

/** Encodes [PgMessage]s back to wire bytes. */
object PgFrames {
    fun encode(message: PgMessage): ByteArray {
        val out = PgPayload()
        if (message.type != PG_UNTYPED) out.byte(message.type.code)
        return out.int32(message.payload.size + 4).bytes(message.payload).toByteArray()
    }
}

/**
 * Splits one direction of a connection into [PgMessage]s, whatever the read boundaries. A client stream starts in the
 * [startupPhase]: untyped frames until the StartupMessage (an SSLRequest or GSSENCRequest is followed by another).
 * Frames past [maxPayload] keep their first bytes and the rest is skipped. A malformed length stops the reader.
 */
class PgFrameReader(
    startupPhase: Boolean,
    private val maxPayload: Int = 16 * 1024 * 1024,
) {
    private var untyped = startupPhase
    private val header = ByteArray(5)
    private var headerFill = 0
    private var type = PG_UNTYPED
    private var payload: ByteArray? = null
    private var payloadFill = 0
    private var cut = false
    private var skip = 0L
    private var broken = false

    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): List<PgMessage> {
        val out = ArrayList<PgMessage>()
        var i = offset
        val end = offset + length
        while (i < end && !broken) {
            val body = payload
            when {
                skip > 0 -> {
                    val n = minOf(skip, (end - i).toLong()).toInt()
                    skip -= n
                    i += n
                }
                body != null -> {
                    val n = minOf(body.size - payloadFill, end - i)
                    System.arraycopy(bytes, i, body, payloadFill, n)
                    payloadFill += n
                    i += n
                    if (payloadFill == body.size) out += finish(body)
                }
                else -> {
                    val headerSize = if (untyped) 4 else 5
                    val n = minOf(headerSize - headerFill, end - i)
                    System.arraycopy(bytes, i, header, headerFill, n)
                    headerFill += n
                    i += n
                    if (headerFill == headerSize) startFrame(headerSize)?.let { out += it }
                }
            }
        }
        return out
    }

    private fun startFrame(headerSize: Int): PgMessage? {
        type = if (untyped) PG_UNTYPED else (header[0].toInt() and 0xFF).toChar()
        val lengthAt = headerSize - 4
        val length =
            (header[lengthAt].toInt() and 0xFF shl 24) or (header[lengthAt + 1].toInt() and 0xFF shl 16) or
                (header[lengthAt + 2].toInt() and 0xFF shl 8) or (header[lengthAt + 3].toInt() and 0xFF)
        headerFill = 0
        if (length < 4) {
            broken = true
            return null
        }
        val size = length - 4
        cut = size > maxPayload
        skip = if (cut) (size - maxPayload).toLong() else 0L
        val body = ByteArray(minOf(size, maxPayload))
        payloadFill = 0
        if (body.isEmpty()) return finish(body)
        payload = body
        return null
    }

    private fun finish(body: ByteArray): PgMessage {
        payload = null
        if (untyped) {
            val code = if (body.size >= 4) PgReader(body).int32() else 0
            untyped = code == SSL_REQUEST || code == GSSENC_REQUEST
        }
        return PgMessage(type, body, cut)
    }
}

/** Builds a message payload in wire order (big-endian integers, NUL-terminated strings). */
class PgPayload {
    private val out = ByteArrayOutputStream()

    fun byte(v: Int) = apply { out.write(v) }

    fun int16(v: Int) =
        apply {
            out.write(v shr 8 and 0xFF)
            out.write(v and 0xFF)
        }

    fun int32(v: Int) = int16(v shr 16).int16(v)

    fun int64(v: Long) = int32((v shr 32).toInt()).int32(v.toInt())

    fun cstring(s: String) = bytes(s.toByteArray(UTF_8)).byte(0)

    fun bytes(b: ByteArray) = apply { out.write(b) }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Reads a message payload; throws on underflow, which callers catch. */
internal class PgReader(
    private val b: ByteArray,
) {
    var pos = 0
        private set

    val remaining get() = b.size - pos

    fun byte(): Int {
        if (pos >= b.size) throw IndexOutOfBoundsException("payload ended")
        return b[pos++].toInt() and 0xFF
    }

    fun int16(): Int = ((byte() shl 8) or byte()).toShort().toInt()

    fun int32(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()

    fun cstring(): String {
        val start = pos
        while (pos < b.size && b[pos] != 0.toByte()) pos++
        if (pos >= b.size) throw IndexOutOfBoundsException("unterminated string")
        return String(b, start, pos++ - start, UTF_8)
    }

    fun bytes(n: Int): ByteArray {
        if (n < 0 || n > remaining) throw IndexOutOfBoundsException("payload ended")
        return b.copyOfRange(pos, pos + n).also { pos += n }
    }
}
