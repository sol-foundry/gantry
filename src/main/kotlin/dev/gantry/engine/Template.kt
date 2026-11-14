package dev.gantry.engine

import dev.gantry.gateway.apisixConfigBlock
import dev.gantry.gateway.apisixRoutesBlock
import dev.gantry.gateway.authorizerServicesBlock
import java.net.URLEncoder

/** Placeholder values available while rendering a service's files: `app`, `opt.x`, `port.x`, ... */
typealias Context = Map<String, String>

/**
 * Renders `{{key}}` placeholders and `{{@block}}` computed blocks used by catalog templates.
 * Computed blocks are whole lines whose content depends on options (queue lists, search attributes).
 */
object Template {
    private val placeholder = Regex("""\{\{\s*([a-zA-Z0-9_.]+)\s*(?:\|\s*([a-z]+)\s*)?}}""")
    private val computedLine = Regex("""(?m)^\{\{@([a-z_]+)}}\n?""")

    private val computedBlocks: Map<String, (Context) -> List<String>> =
        mapOf(
            "queues_block" to ::queuesBlock,
            "temporal_search_attrs" to ::temporalSearchAttributes,
            "valkey_persistence_args" to ::valkeyPersistenceArgs,
            "valkey_data_mount" to ::valkeyDataMount,
            "kafka_create_topics" to ::kafkaCreateTopics,
            "apisix_config" to ::apisixConfigBlock,
            "apisix_routes" to ::apisixRoutesBlock,
            "authorizer_services" to ::authorizerServicesBlock,
        )

    /**
     * With [yamlEscape], values are safe inside a double-quoted YAML scalar. `{{key|url}}` percent-encodes
     * the value for use inside a URL (credentials in connection strings).
     */
    fun render(
        text: String,
        context: Context,
        yamlEscape: Boolean = false,
    ): String =
        placeholder.replace(text) { match ->
            val key = match.groupValues[1]
            val raw = context[key] ?: throw GantryException("unresolved placeholder {{$key}}")
            val value =
                when (val filter = match.groupValues[2]) {
                    "" -> raw
                    "url" -> URLEncoder.encode(raw, Charsets.UTF_8).replace("+", "%20")
                    else -> throw GantryException("unknown filter '|$filter' in {{$key|$filter}}")
                }
            if (yamlEscape) value.replace("\\", "\\\\").replace("\"", "\\\"") else value
        }

    fun expandComputed(
        text: String,
        context: Context,
    ): String =
        computedLine.replace(text) { match ->
            val block = computedBlocks.getValue(match.groupValues[1])(context)
            block.joinToString("") { "$it\n" }
        }

    private fun queuesBlock(context: Context): List<String> =
        parseQueues(context.getValue("opt.queues")).flatMap { queue ->
            buildList {
                add("    ${queue.name} {")
                queue.visibilitySeconds?.let { add("        defaultVisibilityTimeout = $it seconds") }
                add(
                    "        deadLettersQueue { name = \"${queue.deadLetterQueue}\", " +
                        "maxReceiveCount = ${context.getValue("opt.max_receive_count")} }",
                )
                add("    }")
                add("    ${queue.deadLetterQueue} {}")
            }
        }

    private fun valkeyPersistenceArgs(context: Context): List<String> =
        if (context.getValue("opt.persist") == "true") {
            listOf("      - --appendonly", "      - \"yes\"", "      - --save", "      - \"\"")
        } else {
            listOf("      - --save", "      - \"\"")
        }

    /** Persistent data goes to a named volume; otherwise /data is memory-backed so nothing is left behind. */
    private fun valkeyDataMount(context: Context): List<String> =
        if (context.getValue("opt.persist") == "true") {
            listOf("    volumes:", "      - valkey-data:/data")
        } else {
            listOf("    tmpfs:", "      - /data")
        }

    private fun temporalSearchAttributes(context: Context): List<String> =
        context
            .getValue("opt.search_attributes")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { "      - --search-attribute=$it" }

    /** Shell lines for the kafka-init job; names and counts are validated, so they are safe to splice in. */
    private fun kafkaCreateTopics(context: Context): List<String> =
        parseTopics(context.getValue("opt.topics"), context.getValue("opt.partitions")).map { topic ->
            "        /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --create --if-not-exists " +
                "--topic ${topic.name} --partitions ${topic.partitions} && echo \"[kafka-init] ${topic.name}\""
        }
}

data class TopicSpec(
    val name: String,
    val partitions: Int,
)

private val topicNameRule = Regex("^[a-zA-Z0-9._-]{1,249}$")

/** Parses `name[:partitions],...`; topics without a count get [defaultPartitions]. */
fun parseTopics(
    spec: String,
    defaultPartitions: String,
): List<TopicSpec> {
    fun count(value: String) =
        value.toIntOrNull()?.takeIf { it > 0 } ?: throw GantryException("partitions must be a positive number, got '$value'")
    val fallback = count(defaultPartitions)
    return spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { item ->
        val name = item.substringBefore(":")
        if (!topicNameRule.matches(name)) {
            throw GantryException("invalid topic name '$name' (use letters, digits, '.', '_' and '-')")
        }
        TopicSpec(name, item.substringAfter(":", "").ifEmpty { null }?.let(::count) ?: fallback)
    }
}

data class QueueSpec(
    val name: String,
    val visibilitySeconds: String?,
    val deadLetterQueue: String,
)

/** Parses `name[:visibilitySecs[:dlqName]],...`; the dead-letter queue defaults to `<name>-dlq`. */
fun parseQueues(spec: String): List<QueueSpec> =
    spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { item ->
        val parts = item.split(":")
        QueueSpec(
            name = parts[0],
            visibilitySeconds = parts.getOrNull(1)?.ifEmpty { null },
            deadLetterQueue = parts.getOrNull(2)?.ifEmpty { null } ?: "${parts[0]}-dlq",
        )
    }

private val envNeedsQuotes = Regex("""[\s#"'$\\`]""")

/**
 * Quotes a dotenv value only when it contains characters dotenv parsers treat specially. Single quotes are
 * literal in Node's --env-file, Docker Compose and python-dotenv (no `$` expansion, no escapes); a value that
 * itself contains a single quote falls back to double quotes with backslash escapes.
 */
fun quoteEnvValue(value: String): String =
    when {
        !envNeedsQuotes.containsMatchIn(value) -> value
        '\'' !in value -> "'$value'"
        else -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
    }
