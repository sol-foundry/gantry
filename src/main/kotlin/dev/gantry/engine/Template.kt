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
            "dynamodb_create_tables" to ::dynamodbCreateTables,
            "dynamodb_storage" to ::dynamodbStorage,
            "apisix_config" to ::apisixConfigBlock,
            "apisix_routes" to ::apisixRoutesBlock,
            "authorizer_services" to ::authorizerServicesBlock,
            "query_log_proxy" to ::queryLogProxy,
            "db_service_name" to ::dbServiceName,
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

/**
 * Shell lines for the dynamodb-init job: each declared table is created when missing, then its TTL attribute set. Names
 * are validated, so they are safe to splice in; the JSON goes inside single quotes and holds none.
 */
private fun dynamodbCreateTables(context: Context): List<String> =
    parseTables(context.getValue("opt.tables")).flatMap { table ->
        val keys = listOf(table.hash, table.range).filterNotNull()
        val attributes = (keys + table.indexes.flatMap { listOfNotNull(it.hash, it.range) }).distinctBy { it.name }
        val args =
            buildList {
                add("--table-name ${table.name}")
                add("--billing-mode PAY_PER_REQUEST")
                add("--attribute-definitions ${attributes.joinToString(" ") { "AttributeName=${it.name},AttributeType=${it.type}" }}")
                add(
                    "--key-schema ${keySchema(
                        table.hash,
                        table.range,
                    ).joinToString(" ") { "AttributeName=${it.first},KeyType=${it.second}" }}",
                )
                if (table.indexes.isNotEmpty()) {
                    val indexes =
                        table.indexes.joinToString(",", "[", "]") { index ->
                            val schema =
                                keySchema(
                                    index.hash,
                                    index.range,
                                ).joinToString(",") { "{\"AttributeName\":\"${it.first}\",\"KeyType\":\"${it.second}\"}" }
                            "{\"IndexName\":\"${index.name}\",\"KeySchema\":[$schema],\"Projection\":{\"ProjectionType\":\"${index.projection}\"}}"
                        }
                    add("--global-secondary-indexes '$indexes'")
                }
            }
        buildList {
            add("        aws dynamodb describe-table --table-name ${table.name} > /dev/null 2>&1 || {")
            add("          aws dynamodb create-table ${args.joinToString(" ")} > /dev/null && echo \"[dynamodb-init] ${table.name}\"")
            add("        }")
            table.ttl?.let {
                add(
                    "        aws dynamodb update-time-to-live --table-name ${table.name} " +
                        "--time-to-live-specification Enabled=true,AttributeName=$it > /dev/null 2>&1 || true",
                )
            }
        }
    }

private fun keySchema(
    hash: KeySpec,
    range: KeySpec?,
): List<Pair<String, String>> = listOfNotNull(hash.name to "HASH", range?.let { it.name to "RANGE" })

/** In memory unless persist=true, then a named volume; one database whatever the caller's credentials or region. */
private fun dynamodbStorage(context: Context): List<String> =
    if (context.getValue("opt.persist") == "true") {
        listOf(
            "    command: [\"-jar\", \"DynamoDBLocal.jar\", \"-sharedDb\", \"-dbPath\", \"/home/dynamodblocal/data\"]",
            "    volumes:",
            "      - dynamodb-data:/home/dynamodblocal/data",
        )
    } else {
        listOf("    command: [\"-jar\", \"DynamoDBLocal.jar\", \"-sharedDb\", \"-inMemory\"]")
    }

/** A key attribute and its DynamoDB type: `S`, `N` or `B`. */
data class KeySpec(
    val name: String,
    val type: String,
)

data class IndexSpec(
    val name: String,
    val hash: KeySpec,
    val range: KeySpec?,
    /** `ALL` or `KEYS_ONLY`. */
    val projection: String,
)

data class TableSpec(
    val name: String,
    val hash: KeySpec,
    val range: KeySpec?,
    val indexes: List<IndexSpec>,
    val ttl: String?,
)

private val tableNameRule = Regex("^[a-zA-Z0-9_.-]{3,255}$")
private val attributeRule = Regex("^[a-zA-Z0-9_.-]{1,255}$")

/**
 * Parses `name:hash[,range][+index=hash[,range][/keys|/all]]...[~ttlAttribute];...`. A key is `attr` or `attr#N`
 * (`S`, the default, `N` or `B`); an index projects all attributes unless it says `/keys`. For example
 * `sessions:connectionId,topic+topic-index=topic/keys~ttl`.
 */
fun parseTables(spec: String): List<TableSpec> =
    spec.split(";").map { it.trim() }.filter { it.isNotEmpty() }.map { item ->
        fun fail(why: String): Nothing = throw GantryException("invalid table '$item': $why")

        fun key(text: String): KeySpec {
            val name = text.substringBefore("#")
            if (!attributeRule.matches(name)) fail("'$name' is not an attribute name (letters, digits, '.', '_', '-')")
            val type = text.substringAfter("#", "S").uppercase()
            if (type !in setOf("S", "N", "B")) fail("key type must be S, N or B, not '$type'")
            return KeySpec(name, type)
        }

        fun keys(text: String): Pair<KeySpec, KeySpec?> {
            val parts = text.split(",")
            if (parts.size !in 1..2 || parts.any { it.isBlank() }) fail("keys are hash[,range]")
            return key(parts[0]) to parts.getOrNull(1)?.let(::key)
        }
        val name = item.substringBefore(":")
        if (!tableNameRule.matches(name)) fail("a table name is 3-255 letters, digits, '.', '_' or '-'")
        if (":" !in item) fail("say its keys, as name:hash[,range]")
        val ttl = item.substringAfter("~", "").ifEmpty { null }
        ttl?.let { if (!attributeRule.matches(it)) fail("'$it' is not an attribute name") }
        val body = item.substringAfter(":").substringBefore("~").split("+")
        val (hash, range) = keys(body[0])
        val indexes =
            body.drop(1).map { index ->
                val indexName = index.substringBefore("=")
                if (!tableNameRule.matches(indexName) || "=" !in index) fail("an index is name=hash[,range][/keys|/all]")
                val projection =
                    when (val p = index.substringAfter("/", "all").lowercase()) {
                        "all" -> "ALL"
                        "keys" -> "KEYS_ONLY"
                        else -> fail("index projection must be /keys or /all, not /$p")
                    }
                val (indexHash, indexRange) = keys(index.substringAfter("=").substringBefore("/"))
                IndexSpec(indexName, indexHash, indexRange, projection)
            }
        val attributes = (listOfNotNull(hash, range) + indexes.flatMap { listOfNotNull(it.hash, it.range) })
        attributes.groupBy { it.name }.filterValues { specs -> specs.map { it.type }.distinct().size > 1 }.keys.forEach {
            fail("attribute '$it' is given two types")
        }
        TableSpec(name, hash, range, indexes, ttl)
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
