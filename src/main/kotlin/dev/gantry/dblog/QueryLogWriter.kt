package dev.gantry.dblog

import dev.gantry.engine.scanBytesFromEnd
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val json = Json { encodeDefaults = true }

/**
 * Appends [QueryEntry]s as JSON lines to [file]. It is the only writer of the file: past [maxSizeBytes] it rewrites the
 * file in place (truncate and write through the same channel, so the inode survives) keeping the newest lines that
 * total at most half the cap, so trimming happens once per half-cap of new writes rather than on every append.
 */
class QueryLogWriter(
    private val file: Path,
    private val maxSizeBytes: Long,
) : Closeable {
    private val channel: FileChannel
    private var size: Long

    init {
        Files.createDirectories(file.parent)
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        size = channel.size()
    }

    @Synchronized
    fun append(entry: QueryEntry) {
        write(json.encodeToString(entry).toByteArray(Charsets.UTF_8) + NEWLINE)
        if (size > maxSizeBytes) trim()
    }

    @Synchronized
    override fun close() = channel.close()

    private fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        size += bytes.size
    }

    private fun trim() {
        val newest = mutableListOf<ByteArray>()
        var kept = 0L
        scanBytesFromEnd(file) { line ->
            val cost = line.size + 1L
            val fits = kept + cost <= maxSizeBytes / 2
            if (fits) {
                newest += line
                kept += cost
            }
            fits
        }
        channel.truncate(0)
        size = 0
        newest.asReversed().forEach { write(it + NEWLINE) }
    }

    private companion object {
        val NEWLINE = byteArrayOf('\n'.code.toByte())
    }
}
