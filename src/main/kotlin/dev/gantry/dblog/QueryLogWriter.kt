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
 * total at most half the cap, so trimming happens once per half-cap of new writes rather than on every append. The
 * size is always the channel's: Gantry empties the file from inside the container, and an append channel then writes at
 * the new end.
 */
class QueryLogWriter(
    private val file: Path,
    private val maxSizeBytes: Long,
) : Closeable {
    private val channel: FileChannel

    init {
        Files.createDirectories(file.parent)
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
    }

    @Synchronized
    fun append(entry: QueryEntry) {
        write(json.encodeToString(entry).toByteArray(Charsets.UTF_8) + NEWLINE)
        if (channel.size() > maxSizeBytes) trim()
    }

    @Synchronized
    override fun close() = channel.close()

    private fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
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
        newest.asReversed().forEach { write(it + NEWLINE) }
    }

    private companion object {
        val NEWLINE = byteArrayOf('\n'.code.toByte())
    }
}
