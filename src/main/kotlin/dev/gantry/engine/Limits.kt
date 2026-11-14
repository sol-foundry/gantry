package dev.gantry.engine

/**
 * A ceiling on one container: [cpus] cores and [memoryMb] MiB. Ceilings, not reservations: an idle container uses
 * what it needs, but no single one (a restore, a runaway query, a leaking UI) can take the whole Docker VM and with it
 * the machine.
 */
data class ResourceLimit(
    val cpus: Double,
    val memoryMb: Int,
)

/** For a compose service with no entry in [RESOURCE_LIMITS]: pushed authorizers and anything added later. */
val DEFAULT_LIMIT = ResourceLimit(cpus = 1.0, memoryMb = 512)

/** One-shot `*-init` jobs; kafka-init runs Kafka's JVM tools, which want 256 MiB of heap. */
private val INIT_LIMIT = ResourceLimit(cpus = 0.5, memoryMb = 512)

/** Headroom over valkey's own `maxmemory` for its process, buffers and replication backlog. */
private const val VALKEY_OVERHEAD_MB = 256

/**
 * Ceilings by compose service name, sized for local development. The JVM services either set their heap or size it
 * from the container with `-XX:MaxRAMPercentage`, so the memory ceiling is what they plan for, not an OOM kill.
 */
val RESOURCE_LIMITS =
    mapOf(
        "postgres-db" to ResourceLimit(1.5, 1024),
        "mysql-db" to ResourceLimit(1.5, 1024),
        // Without the query log the database runs under the service's own name.
        "postgres" to ResourceLimit(1.5, 1024),
        "mysql" to ResourceLimit(1.5, 1024),
        "temporal" to ResourceLimit(1.0, 512),
        "valkey" to ResourceLimit(0.5, 512 + VALKEY_OVERHEAD_MB),
        "redisinsight" to ResourceLimit(0.5, 512),
        "dynamodb" to ResourceLimit(1.0, 1024),
        "dynamodb-ui" to ResourceLimit(0.25, 256),
        "elasticmq" to ResourceLimit(0.5, 256),
        "kafka" to ResourceLimit(1.0, 1024),
        "kafka-ui" to ResourceLimit(0.5, 512),
        "gateway" to ResourceLimit(1.0, 512),
        "gantry-auth" to ResourceLimit(0.5, 512),
        "gateway-ws" to ResourceLimit(0.5, 256),
    )

/** The query-log proxy runs with `-Xmx512m`; the rest is the JVM's own overhead. */
private val QUERY_LOG_PROXY_LIMIT = ResourceLimit(0.5, 768)

/** `gantry sync` dumps and restores in one container; it is capped so a large copy cannot starve the database. */
val SYNC_LIMIT = ResourceLimit(cpus = 1.0, memoryMb = 1024)

/** `docker run` flags applying [limit]. */
fun ResourceLimit.runFlags(): List<String> = listOf("--cpus=${formatCpus(cpus)}", "--memory=${memoryMb}m")

private fun formatCpus(cpus: Double) = if (cpus == Math.floor(cpus)) cpus.toInt().toString() else cpus.toString()

/** The ceiling for compose service [name] of catalog service [context]`.service`. */
internal fun limitFor(
    name: String,
    context: Context,
): ResourceLimit {
    val service = context["service"]
    return when {
        name == service && service in DATABASE_SERVICES && queryLogOn(context) -> QUERY_LOG_PROXY_LIMIT
        name == "valkey" -> RESOURCE_LIMITS.getValue("valkey").copy(memoryMb = valkeyMemoryMb(context) + VALKEY_OVERHEAD_MB)
        name.endsWith("-init") -> INIT_LIMIT
        else -> RESOURCE_LIMITS[name] ?: DEFAULT_LIMIT
    }
}

private val valkeyMemory = Regex("""^(\d+)\s*(b|k|kb|m|mb|g|gb)?$""", RegexOption.IGNORE_CASE)

/** Valkey's `maxmemory` in MiB (`512mb`, `1gb`, `1073741824`); 512 when it cannot be read. */
private fun valkeyMemoryMb(context: Context): Int {
    val match = valkeyMemory.matchEntire(context["opt.maxmemory"]?.trim().orEmpty()) ?: return 512
    val amount = match.groupValues[1].toLong()
    val bytes =
        when (match.groupValues[2].lowercase()) {
            "k", "kb" -> amount * 1024
            "m", "mb" -> amount * 1024 * 1024
            "g", "gb" -> amount * 1024 * 1024 * 1024
            else -> amount
        }
    return (bytes / (1024 * 1024)).toInt().coerceAtLeast(64)
}

/** A top-level service in a catalog fragment: two spaces, its name, a colon. */
private val SERVICE_NAME = Regex("(?m)^  ([a-zA-Z0-9_-]+):\\s*$")

/**
 * Gives every service in [fragment] a `cpus` and `mem_limit` ceiling ([limitFor]), unless its template already sets
 * one. Compose recreates a container whose limits change, so an app picks them up on its next `up`.
 */
internal fun withResourceLimits(
    fragment: String,
    context: Context,
): String {
    val headers = SERVICE_NAME.findAll(fragment).toList()
    if (headers.isEmpty()) return fragment
    val out = StringBuilder(fragment.substring(0, headers.first().range.first))
    val ends = headers.drop(1).map { it.range.first } + fragment.length
    headers.zip(ends).forEach { (header, end) ->
        val block = fragment.substring(header.range.first, end)
        val headerEnd = block.indexOf('\n') + 1
        out.append(block, 0, headerEnd)
        if (!Regex("(?m)^    (cpus|mem_limit|deploy):").containsMatchIn(block)) {
            val limit = limitFor(header.groupValues[1], context)
            out.append("    cpus: ${formatCpus(limit.cpus)}\n    mem_limit: ${limit.memoryMb}m\n")
        }
        out.append(block, headerEnd, block.length)
    }
    return out.toString()
}
