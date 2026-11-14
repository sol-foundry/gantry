package dev.gantry.engine

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private const val CHUNK = 64 * 1024

/**
 * Calls [visit] with each non-blank line of [file], last line first, reading [CHUNK] bytes at a time from the end,
 * until it returns false. A NUL also ends a line: a stale append offset leaves a run of them in front of the next line. A missing or unreadable file has no lines, and so has anything but a regular file (a
 * symlink is never followed).
 */
internal fun scanBytesFromEnd(
    file: Path,
    visit: (ByteArray) -> Boolean,
) {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return
    try {
        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            var position = channel.size()
            var carry = ByteArray(0)
            while (position > 0) {
                val start = maxOf(0L, position - CHUNK)
                val buffer = ByteBuffer.allocate((position - start).toInt())
                while (buffer.hasRemaining()) if (channel.read(buffer, start + buffer.position()) < 0) break
                val data = buffer.array() + carry
                var end = data.size
                for (i in data.indices.reversed()) {
                    if (data[i] != '\n'.code.toByte() && data[i] != 0.toByte()) continue
                    if (!emit(data, i + 1, end, visit)) return
                    end = i
                }
                carry = data.copyOfRange(0, end)
                position = start
            }
            emit(carry, 0, carry.size, visit)
        }
    } catch (_: IOException) {
        // No readable file is no calls.
    }
}

/** Hands the line `data[from, to)` to [visit] unless it is blank; false when [visit] wants to stop. */
private fun emit(
    data: ByteArray,
    from: Int,
    to: Int,
    visit: (ByteArray) -> Boolean,
): Boolean {
    var end = to
    if (end > from && data[end - 1] == '\r'.code.toByte()) end--
    return end == from || visit(data.copyOfRange(from, end))
}
