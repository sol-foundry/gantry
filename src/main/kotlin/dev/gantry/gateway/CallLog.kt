package dev.gantry.gateway

import dev.gantry.engine.Engine
import dev.gantry.engine.ExecResult
import dev.gantry.engine.GantryException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One proxied call: what APISIX logged, joined with gantry-auth's decision for it and redacted. */
@Serializable
data class Call(
    val id: String,
    val time: String,
    val method: String,
    val uri: String,
    /** APISIX's file-logger does not record the rewritten upstream URI, so this is null. */
    val upstreamUri: String?,
    val status: Int,
    val latencyMs: Long,
    val route: String?,
    val userId: String?,
    val authDecision: String?,
    val authReason: String?,
    val requestHeaders: Map<String, String>,
    val requestBody: String?,
    val responseHeaders: Map<String, String>,
    val responseBody: String?,
    /** True when a body was cut at the configured size. */
    val truncated: Boolean,
)

private const val CALLS_FILE = "calls.log"
private const val AUTH_FILE = "auth.log"

/** Where the gateway and gantry-auth containers mount `gateway-log`. */
private const val CONTAINER_LOG_DIR = "/gantry-log"
private const val REDACTED = "[redacted]"
private const val CHUNK = 64 * 1024
private const val TRIM_BYTES_PER_CALL = 2048L
private val USER_KEYS = listOf("userId", "uid", "sub")
private val warnedUnwritable = AtomicBoolean(false)
private val warnedRewriteFailed = AtomicBoolean(false)

/** How many times a file was scanned to decide on a trim; tests use it to see that unchanged files are not rescanned. */
internal val trimScans = AtomicInteger()

/** One monitor per file, process-wide, so two readers never interleave a truncate and a write. */
private val trimLocks = ConcurrentHashMap<Path, Any>()

/** Per file, the size at which a scan last found no more than `maxCalls` lines. */
private val scannedShort = ConcurrentHashMap<Path, Long>()

/**
 * Reads the gateway's call log directory [dir] (`gateway-log`): `calls.log` from APISIX's file-logger and `auth.log`
 * from gantry-auth. Files are read from the end, so only the newest lines are ever touched. Redaction and body
 * cutting happen here, at read time, so the files keep the full record. Besides [LogSettings.redact], the headers in
 * [secretHeaders] and the query parameters in [tokenParams] are redacted (names in any case).
 *
 * [writer] replaces a file's whole content from inside the container that appends to it. Its append offset lives in
 * the VM, so a truncate from the host side of a virtiofs mount leaves it stale, and its next line lands at the old
 * end with the gap filled with NULs. Without a writer, or with the container stopped, files are rewritten here.
 */
class CallLog(
    private val dir: Path,
    private val settings: LogSettings,
    secretHeaders: Set<String> = emptySet(),
    tokenParams: Set<String> = emptySet(),
    private val writer: ((fileName: String, content: ByteArray) -> ExecResult)? = null,
) {
    private val redact = (settings.redact + secretHeaders).map { it.lowercase() }.toSet()
    private val redactParams = tokenParams.map { it.lowercase() }.toSet()

    companion object {
        /**
         * The call log for [config]: on top of its `log.redact`, every header a route (or the default, or all of them) sets from a
         * `{{secret:...}}`, and every query parameter an authorizer reads its token from, are redacted.
         */
        fun forConfig(
            dir: Path,
            config: GatewayConfig,
            writer: ((fileName: String, content: ByteArray) -> ExecResult)? = null,
        ): CallLog {
            val headers = config.routes.map { config.headersFor(it) } + config.defaultHeaders()
            val secretHeaders = headers.flatMap { h -> h.filterValues { SECRET_REF.containsMatchIn(it) }.keys }.toSet()
            val tokenParams =
                config.authorizers.values
                    .map { it.tokenFrom }
                    .filter { it.startsWith("query:") }
                    .map { it.removePrefix("query:") }
                    .toSet()
            return CallLog(dir, config.log, secretHeaders, tokenParams, writer)
        }
    }

    /** The newest calls first, at most [limit]; [status] is a class like `4xx` or a code, [path] a glob over the path without its query. */
    fun recent(
        limit: Int = 100,
        status: String? = null,
        path: String? = null,
    ): List<Call> {
        val statusMatch = status?.let(::statusMatcher)
        val pathMatch = path?.let { globToRegex(it) }
        trimIfLarge()
        val out = mutableListOf<Call>()
        if (limit <= 0) return out
        val auth by lazy { authDecisions() }
        scanFromEnd(dir.resolve(CALLS_FILE), settings.maxCalls) { raw ->
            val call = parseCall(raw, auth)
            if (call != null &&
                (statusMatch == null || statusMatch(call.status)) &&
                (pathMatch == null || pathMatch.matches(call.uri.substringBefore('?')))
            ) {
                out += call
            }
            out.size < limit
        }
        return out
    }

    /** The call with request id [id], or null when it is not among the retained calls. */
    fun get(id: String): Call? {
        var found: Call? = null
        val auth by lazy { authDecisions() }
        scanFromEnd(dir.resolve(CALLS_FILE), settings.maxCalls) { raw ->
            found = parseCall(raw, auth)?.takeIf { it.id == id }
            found == null
        }
        return found
    }

    /** Keeps the newest [LogSettings.maxCalls] lines of both files, rewriting each in place. */
    fun trim() {
        trimFile(dir.resolve(CALLS_FILE))
        trimFile(dir.resolve(AUTH_FILE))
    }

    /** Empties both files in place (APISIX's append handle stays valid), so the log starts over. */
    fun clear() {
        val failures =
            listOf(CALLS_FILE, AUTH_FILE).map { dir.resolve(it) }.mapNotNull { file ->
                synchronized(trimLocks.computeIfAbsent(file.toAbsolutePath()) { Any() }) {
                    // Never through a link, as for trimming.
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return@synchronized null
                    try {
                        rewrite(file, ByteArray(0))
                        scannedShort.remove(file.toAbsolutePath())
                        null
                    } catch (e: IOException) {
                        "$file: ${e.message}"
                    }
                }
            }
        if (failures.isNotEmpty()) throw GantryException("could not clear ${failures.joinToString("; ")}")
    }

    private fun trimIfLarge() {
        val threshold = settings.maxCalls * TRIM_BYTES_PER_CALL
        listOf(CALLS_FILE, AUTH_FILE).map { dir.resolve(it) }.forEach { file ->
            val size =
                try {
                    Files.size(file)
                } catch (_: IOException) {
                    0L
                }
            // A file a scan found short is looked at again only after it grew by a margin, so polling stays cheap.
            val scanned = scannedShort[file.toAbsolutePath()]
            if (size > threshold && (scanned == null || size >= scanned + threshold / 4)) trimFile(file)
        }
    }

    /**
     * Rewrites [file] to its newest lines with the same inode, truncating and writing in place: APISIX keeps an
     * append handle open, which a rename would orphan.
     */
    private fun trimFile(file: Path) {
        synchronized(trimLocks.computeIfAbsent(file.toAbsolutePath()) { Any() }) { trimLocked(file) }
    }

    private fun trimLocked(file: Path) {
        // Never through a link: a planted symlink must not get some other file truncated.
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return
        // Still possible on Linux for a log the container created before gantry pre-created them as the host user;
        // skipped up front so such a file is not rescanned on every read.
        if (!Files.isWritable(file)) {
            if (warnedUnwritable.compareAndSet(false, true)) System.err.println("gantry: $file is not writable; not trimming the call log")
            return
        }
        try {
            trimScans.incrementAndGet()
            val sizeBefore = Files.size(file)
            val newest = mutableListOf<ByteArray>()
            scanBytesFromEnd(file) { line ->
                newest += line
                newest.size <= settings.maxCalls
            }
            val keep = newest.take(settings.maxCalls).reversed()
            val keptBytes = keep.sumOf { it.size + 1L }
            // Few lines can still mean a huge file: a NUL gap from a stale append offset is dropped here too.
            val bloated = sizeBefore > keptBytes + settings.maxCalls * TRIM_BYTES_PER_CALL / 4
            if (newest.size <= settings.maxCalls && !bloated) {
                scannedShort[file.toAbsolutePath()] = sizeBefore
                return
            }
            val out = ByteBuffer.allocate(keptBytes.toInt())
            keep.forEach { out.put(it).put('\n'.code.toByte()) }
            try {
                rewrite(file, out.array())
                scannedShort.remove(file.toAbsolutePath())
            } catch (e: IOException) {
                // Retried only once the file has grown, not on every poll.
                scannedShort[file.toAbsolutePath()] = sizeBefore
                if (warnedRewriteFailed.compareAndSet(false, true)) System.err.println("gantry: could not trim $file: ${e.message}")
            }
        } catch (e: IOException) {
            if (warnedUnwritable.compareAndSet(false, true)) System.err.println("gantry: could not trim $file: ${e.message}")
        }
    }

    /** Replaces [file]'s content with [content], keeping its inode; through [writer] whenever its container runs. */
    private fun rewrite(
        file: Path,
        content: ByteArray,
    ) {
        when (writer?.invoke(file.fileName.toString(), content)) {
            ExecResult.DONE -> return
            ExecResult.FAILED -> throw IOException("the container writing it could not rewrite it")
            ExecResult.NOT_RUNNING, null -> {
                val options = arrayOf<OpenOption>(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
                FileChannel.open(file, *options).use { ch ->
                    val buffer = ByteBuffer.wrap(content)
                    while (buffer.hasRemaining()) ch.write(buffer)
                }
            }
        }
    }

    private fun statusMatcher(filter: String): (Int) -> Boolean {
        val klass =
            Regex("([1-5])xx", RegexOption.IGNORE_CASE)
                .matchEntire(filter)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        if (klass != null) return { it / 100 == klass }
        val code = filter.toIntOrNull() ?: throw GantryException("status must be a class like 4xx or a code like 404")
        return { it == code }
    }

    /** The newest auth decision per request id. */
    private fun authDecisions(): Map<String, AuthEntry> {
        val byId = HashMap<String, AuthEntry>()
        scanFromEnd(dir.resolve(AUTH_FILE), settings.maxCalls) { raw ->
            val line = parseObject(raw)
            val id = line?.text("requestId")
            if (line != null && id != null && id !in byId) {
                val context = line["context"] as? JsonObject
                byId[id] =
                    AuthEntry(
                        decision = line.text("decision"),
                        reason = line.text("reason"),
                        userId = USER_KEYS.firstNotNullOfOrNull { context?.text(it) },
                    )
            }
            true
        }
        return byId
    }

    private class AuthEntry(
        val decision: String?,
        val reason: String?,
        val userId: String?,
    )

    private fun parseCall(
        raw: String,
        auth: Map<String, AuthEntry>,
    ): Call? {
        val line = parseObject(raw) ?: return null
        val request = line["request"] as? JsonObject ?: return null
        val response = line["response"] as? JsonObject ?: return null
        val method = request.text("method") ?: return null
        val uri = request.text("uri") ?: return null
        val status = (response["status"] as? JsonPrimitive)?.intOrNull ?: return null
        val requestHeaders = redactedHeaders(request["headers"])
        val id = requestHeaders["x-request-id"] ?: stableId(raw)
        val authEntry = auth[id]
        val requestBody = cut(request.text("body"))
        val responseBody = cut(response.text("body"))
        val started = (line["start_time"] as? JsonPrimitive)?.longOrNull
        return Call(
            id = id,
            time = started?.let { Instant.ofEpochMilli(it).toString() } ?: "",
            method = method,
            uri = redactedUri(uri),
            upstreamUri = null,
            status = status,
            latencyMs = Math.round((line["latency"] as? JsonPrimitive)?.doubleOrNull ?: 0.0),
            route = line.text("route_id"),
            userId = authEntry?.userId,
            authDecision = authEntry?.decision,
            authReason = authEntry?.reason,
            requestHeaders = requestHeaders,
            requestBody = requestBody?.first,
            responseHeaders = redactedHeaders(response["headers"]),
            responseBody = responseBody?.first,
            truncated = requestBody?.second == true || responseBody?.second == true,
        )
    }

    /** [uri] with the value of every token query parameter replaced. */
    private fun redactedUri(uri: String): String {
        if (redactParams.isEmpty() || '?' !in uri) return uri
        val path = uri.substringBefore('?')
        val query =
            uri.substringAfter('?').split('&').joinToString("&") { part ->
                val name = part.substringBefore('=')
                val decoded = runCatching { URLDecoder.decode(name, Charsets.UTF_8) }.getOrDefault(name)
                if ('=' in part && decoded.lowercase() in redactParams) "$name=$REDACTED" else part
            }
        return "$path?$query"
    }

    private fun redactedHeaders(element: JsonElement?): Map<String, String> {
        val out = linkedMapOf<String, String>()
        (element as? JsonObject)?.forEach { (name, value) ->
            val lower = name.lowercase()
            val text =
                when (value) {
                    is JsonArray -> value.joinToString(", ") { (it as? JsonPrimitive)?.content ?: it.toString() }
                    is JsonPrimitive -> value.content
                    else -> value.toString()
                }
            out[lower] = if (lower in redact) REDACTED else text
        }
        return out
    }

    /** [body] cut to the configured size without splitting a code point, and whether it was cut. */
    private fun cut(body: String?): Pair<String, Boolean>? {
        if (body == null) return null
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size <= settings.maxBodyBytes) return body to false
        var end = settings.maxBodyBytes
        // Continuation bytes are 10xxxxxx: step back to the start of the code point the cut landed in.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) to true
    }

    private fun stableId(raw: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }

    private fun parseObject(raw: String): JsonObject? =
        try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * The call log of [app], redacting by the config the gateway runs (`gateway.applied.yml`), else `gateway.yml`; the
 * default settings when neither parses.
 */
fun Engine.callLog(app: String): CallLog {
    val dir = appDir(app)
    val applied = dir.resolve(APPLIED_CONFIG_FILE).takeIf { Files.exists(it) }?.let(Files::readString)
    val config =
        listOfNotNull(applied, configText(dir)).firstNotNullOfOrNull { text ->
            try {
                parseGatewayConfig(text)
            } catch (_: GantryException) {
                null
            }
        } ?: GatewayConfig()
    val writers = mapOf(CALLS_FILE to "$app-gateway", AUTH_FILE to "$app-gantry-auth")
    return CallLog.forConfig(dir.resolve(LOG_DIR), config) { fileName, content ->
        docker.execWithInput(writers.getValue(fileName), listOf("sh", "-c", "cat > $CONTAINER_LOG_DIR/$fileName"), content)
    }
}

/** Calls [visit] with each non-blank line of [file] as text, newest first, until it returns false or [max] lines were visited. */
private fun scanFromEnd(
    file: Path,
    max: Int,
    visit: (String) -> Boolean,
) {
    var seen = 0
    scanBytesFromEnd(file) { bytes ->
        seen++
        visit(String(bytes, Charsets.UTF_8)) && seen < max
    }
}

/**
 * Calls [visit] with each non-blank line of [file], last line first, reading [CHUNK] bytes at a time from the end,
 * until it returns false. A NUL also ends a line: a stale append offset leaves a run of them in front of the next line. A missing or unreadable file has no lines, and so has anything but a regular file (a
 * symlink is never followed).
 */
private fun scanBytesFromEnd(
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
