package dev.gantry.dblog.mysql

import dev.gantry.dblog.postgres.PgValues
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8

/** A decoded value: its [text] (null for SQL NULL) and whether the bytes behind it were [cut]. */
internal class MyValue(
    val text: String?,
    val cut: Boolean,
)

/**
 * Renders MySQL values as text: text-protocol values as sent, binary-protocol values decoded by column type (integers,
 * floats, decimals, dates and times, strings, JSON), anything else or binary data as `\x` hex. A value longer than
 * maxBytes is read only up to about maxBytes.
 */
internal object MyValues {
    const val DECIMAL = 0x00
    const val TINY = 0x01
    const val SHORT = 0x02
    const val LONG = 0x03
    const val FLOAT = 0x04
    const val DOUBLE = 0x05
    const val NULL = 0x06
    const val TIMESTAMP = 0x07
    const val LONGLONG = 0x08
    const val INT24 = 0x09
    const val DATE = 0x0A
    const val TIME = 0x0B
    const val DATETIME = 0x0C
    const val YEAR = 0x0D
    const val NEWDATE = 0x0E
    const val BIT = 0x10
    const val GEOMETRY = 0xFF
    const val BINARY_CHARSET = 63

    private val dateLengths = setOf(0, 4, 7, 11)
    private val blobs = 0xF9..0xFC
    private val strings = setOf(0x0F, 0xF7, 0xF8, 0xFD, 0xFE) + blobs

    /** How string bytes are shown. */
    enum class Bytes { TEXT, HEX, TEXT_OR_HEX }

    /** How a column's bytes are shown: binary-collation strings and blobs, bits and geometry as hex. */
    fun columnBytes(
        type: Int,
        charset: Int,
    ): Bytes =
        when {
            type == BIT || type == GEOMETRY -> Bytes.HEX
            type in strings && charset == BINARY_CHARSET -> Bytes.HEX
            else -> Bytes.TEXT
        }

    /** Parameters carry no charset: string bytes show as text when they are valid UTF-8, else as hex. */
    fun paramBytes(type: Int): Bytes =
        when (type) {
            BIT, GEOMETRY -> Bytes.HEX
            in strings -> Bytes.TEXT_OR_HEX
            else -> Bytes.TEXT
        }

    /** One text-protocol value (a length-encoded string, or NULL). */
    fun readText(
        r: MyReader,
        bytes: Bytes,
        maxBytes: Int,
    ): MyValue {
        val n = r.lenenc()
        if (n < 0) return MyValue(null, false)
        return MyValue(render(r.slice(n, keep(maxBytes)), bytes, maxBytes, n > maxBytes), n > maxBytes)
    }

    /** One binary-protocol value of [type]. */
    fun readBinary(
        r: MyReader,
        type: Int,
        unsigned: Boolean,
        bytes: Bytes,
        maxBytes: Int,
    ): MyValue {
        val text =
            when (type) {
                NULL -> null
                TINY -> r.byte().let { if (unsigned) it else it.toByte().toInt() }.toString()
                SHORT, YEAR -> r.int16().let { if (unsigned || type == YEAR) it else it.toShort().toInt() }.toString()
                LONG, INT24 -> r.int32().let { if (unsigned) it.toUInt().toString() else it.toString() }
                LONGLONG -> r.int64().let { if (unsigned) it.toULong().toString() else it.toString() }
                FLOAT -> Float.fromBits(r.int32()).toString()
                DOUBLE -> Double.fromBits(r.int64()).toString()
                DATE, NEWDATE -> dateTime(r, withTime = false)
                DATETIME, TIMESTAMP -> dateTime(r, withTime = true)
                TIME -> time(r)
                else -> {
                    val n = r.lenenc()
                    if (n < 0) return MyValue(null, false)
                    return MyValue(render(r.slice(n, keep(maxBytes)), bytes, maxBytes, n > maxBytes), n > maxBytes)
                }
            }
        return MyValue(text, false)
    }

    /** Reads an arbitrary value of [type] in binary form, for a field whose type alone tells its size. */
    fun binarySize(
        r: MyReader,
        type: Int,
    ) {
        readBinary(r, type, unsigned = false, bytes = Bytes.HEX, maxBytes = 0)
    }

    fun render(
        raw: ByteArray,
        bytes: Bytes,
        maxBytes: Int,
        cut: Boolean,
    ): String =
        when (bytes) {
            Bytes.HEX -> hex(raw, maxBytes, cut)
            Bytes.TEXT -> text(raw, maxBytes)
            Bytes.TEXT_OR_HEX -> strictText(raw, maxBytes, cut) ?: hex(raw, maxBytes, cut)
        }

    private fun keep(maxBytes: Int) = if (maxBytes == Int.MAX_VALUE) Int.MAX_VALUE else maxBytes + 1

    private fun hex(
        raw: ByteArray,
        maxBytes: Int,
        cut: Boolean,
    ) = if (cut) PgValues.hex(raw, maxOf(0, (maxBytes - 2) / 2)) else PgValues.hex(raw)

    // A multi-byte character split at the cut is dropped rather than replaced.
    private fun text(
        raw: ByteArray,
        maxBytes: Int,
    ): String {
        if (raw.size <= maxBytes) return String(raw, UTF_8)
        val decoder = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE)
        val out = CharBuffer.allocate(maxBytes)
        decoder.decode(ByteBuffer.wrap(raw, 0, maxBytes), out, true)
        return out.flip().toString()
    }

    private fun strictText(
        raw: ByteArray,
        maxBytes: Int,
        cut: Boolean,
    ): String? {
        val n = minOf(raw.size, maxBytes)
        val decoder = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = CharBuffer.allocate(n)
        val result = decoder.decode(ByteBuffer.wrap(raw, 0, n), out, !cut && n == raw.size)
        if (result.isError) return null
        return out.flip().toString()
    }

    private fun dateTime(
        r: MyReader,
        withTime: Boolean,
    ): String {
        val length = r.byte()
        if (length !in dateLengths) throw IllegalStateException("bad date length $length")
        val year = if (length >= 4) r.int16() else 0
        val month = if (length >= 4) r.byte() else 0
        val day = if (length >= 4) r.byte() else 0
        val hour = if (length >= 7) r.byte() else 0
        val minute = if (length >= 7) r.byte() else 0
        val second = if (length >= 7) r.byte() else 0
        val micros = if (length >= 11) r.int32() else 0
        val date = "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}"
        if (!withTime) return date
        return "$date ${pad(hour, 2)}:${pad(minute, 2)}:${pad(second, 2)}${fraction(micros)}"
    }

    private fun time(r: MyReader): String {
        val length = r.byte()
        if (length == 0) return "00:00:00"
        if (length != 8 && length != 12) throw IllegalStateException("bad time length $length")
        val negative = r.byte() == 1
        val days = r.int32().toLong() and 0xFFFFFFFFL
        val hours = days * 24 + r.byte()
        val minute = r.byte()
        val second = r.byte()
        val micros = if (length == 12) r.int32() else 0
        return "${if (negative) "-" else ""}${pad(hours, 2)}:${pad(minute, 2)}:${pad(second, 2)}${fraction(micros)}"
    }

    private fun fraction(micros: Int) = if (micros == 0) "" else "." + pad(micros, 6)

    private fun pad(
        v: Number,
        width: Int,
    ) = v.toString().padStart(width, '0')
}
