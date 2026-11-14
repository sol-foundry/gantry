package dev.gantry.dblog.postgres

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Renders Postgres parameter and column values as text: text format as sent, binary decoded by type OID, else hex. */
object PgValues {
    private val epochDate = LocalDate.of(2000, 1, 1)
    private val epoch = epochDate.atStartOfDay()
    private val timeFormat = DateTimeFormatter.ISO_LOCAL_TIME

    fun decode(
        typeOid: Int,
        format: Int,
        bytes: ByteArray,
    ): String {
        if (format == 0) return String(bytes, UTF_8)
        return runCatching { binary(typeOid, bytes) }.getOrNull() ?: hex(bytes)
    }

    fun hex(bytes: ByteArray): String =
        buildString(2 + bytes.size * 2) {
            append("\\x")
            bytes.forEach { append("%02x".format(it.toInt() and 0xFF)) }
        }

    private fun binary(
        type: Int,
        b: ByteArray,
    ): String? {
        val buf = ByteBuffer.wrap(b)

        fun sized(n: Int) = b.size == n
        return when (type) {
            16 -> if (sized(1)) (b[0].toInt() != 0).toString() else null
            21 -> if (sized(2)) buf.short.toString() else null
            23 -> if (sized(4)) buf.int.toString() else null
            26 -> if (sized(4)) (buf.int.toLong() and 0xFFFFFFFFL).toString() else null
            20 -> if (sized(8)) buf.long.toString() else null
            700 -> if (sized(4)) buf.float.toString() else null
            701 -> if (sized(8)) buf.double.toString() else null
            2950 -> if (sized(16)) UUID(buf.long, buf.long).toString() else null
            1082 -> if (sized(4)) date(buf.int) else null
            1114 -> if (sized(8)) timestamp(buf.long, "") else null
            1184 -> if (sized(8)) timestamp(buf.long, "+00") else null
            1700 -> numeric(buf)
            18, 19, 25, 114, 705, 1042, 1043 -> String(b, UTF_8)
            3802 -> if (b.isNotEmpty() && b[0].toInt() == 1) String(b, 1, b.size - 1, UTF_8) else null
            else -> null
        }
    }

    private fun date(days: Int) =
        when (days) {
            Int.MAX_VALUE -> "infinity"
            Int.MIN_VALUE -> "-infinity"
            else -> epochDate.plusDays(days.toLong()).toString()
        }

    private fun timestamp(
        micros: Long,
        zone: String,
    ): String =
        when (micros) {
            Long.MAX_VALUE -> "infinity"
            Long.MIN_VALUE -> "-infinity"
            else -> {
                val t: LocalDateTime = epoch.plus(micros, ChronoUnit.MICROS)
                "${t.toLocalDate()} ${t.toLocalTime().format(timeFormat)}$zone"
            }
        }

    // numeric: ndigits, weight, sign, dscale, then base-10000 digits; digit i is worth 10000^(weight - i).
    private fun numeric(buf: ByteBuffer): String? {
        val count = buf.short.toInt()
        val weight = buf.short.toInt()
        val sign = buf.short.toInt() and 0xFFFF
        val scale = buf.short.toInt()
        when (sign) {
            0xC000 -> return "NaN"
            0xD000 -> return "Infinity"
            0xF000 -> return "-Infinity"
            0x0000, 0x4000 -> Unit
            else -> return null
        }
        if (buf.remaining() != count * 2) return null
        var value = BigDecimal.ZERO
        for (i in 0 until count) {
            value += BigDecimal.valueOf(buf.short.toLong()).scaleByPowerOfTen(4 * (weight - i))
        }
        value = value.setScale(scale, java.math.RoundingMode.DOWN)
        return (if (sign == 0x4000) value.negate() else value).toPlainString()
    }
}
