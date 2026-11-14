package dev.gantry.engine

/** The stock JRE image the query-log proxy runs gantry's jars in. */
const val QUERY_LOG_IMAGE = "eclipse-temurin:17-jre"

/** The database's own host port, for gantry's tools; `main` is the proxy's while the query log is on. */
const val DIRECT_PORT = "direct"

private val databasePorts = mapOf("postgres" to 5432, "mysql" to 3306)

internal fun queryLogOn(context: Context) = context["opt.query_log"] == "true"

/** The compose key the database runs under: `<service>-db` behind the proxy, else the service's own name. */
fun databaseServiceName(
    service: String,
    queryLog: Boolean,
) = if (queryLog) "$service-db" else service

/** The database's header, container name and published port: `direct` behind the proxy, `main` without it. */
internal fun dbServiceName(context: Context): List<String> {
    val service = context.getValue("service")
    val on = queryLogOn(context)
    val name = databaseServiceName(service, on)
    return listOf(
        "  $name:",
        "    container_name: \"{{app}}-$name\"",
        "    ports:",
        "      - \"127.0.0.1:{{port.${if (on) DIRECT_PORT else "main"}}}:${databasePorts.getValue(service)}\"",
    )
}

/** The proxy on the service's own name and `main` port, relaying to the database inside the app's network. */
internal fun queryLogProxy(context: Context): List<String> {
    if (!queryLogOn(context)) return emptyList()
    val service = context.getValue("service")
    val port = databasePorts.getValue(service)
    val db = databaseServiceName(service, true)

    fun limit(option: String): String {
        val value = context.getValue("opt.$option")
        value.toLongOrNull()?.takeIf { it > 0 && (option == "query_log_max_size" || it <= Int.MAX_VALUE) }
            ?: throw GantryException("option '$option' of '$service' must be a positive whole number, got '$value'")
        return value
    }
    val args =
        listOf(
            "java",
            "-Xmx512m",
            "-XX:+ExitOnOutOfMemoryError",
            "-cp",
            "\"/gantry/lib/*\"",
            "dev.gantry.dblog.QueryLogServerKt",
            "--dialect",
            service,
            "--listen",
            "\"$port\"",
            "--upstream",
            "\"$db:$port\"",
            "--log",
            "/db-log/$service.jsonl",
            "--tls",
            "\"/tls/{{app}}.p12\"",
            "--tls-password-file",
            "\"/tls/{{app}}.password\"",
            "--max-rows",
            "\"${limit("query_log_max_rows")}\"",
            "--max-bytes",
            "\"${limit("query_log_max_bytes")}\"",
            "--max-size",
            "\"${limit("query_log_max_size")}\"",
            "--service",
            service,
        )
    return listOf(
        "  $service:",
        "    image: \"$QUERY_LOG_IMAGE\"",
        "    container_name: \"{{app}}-$service\"",
        "    command:",
    ) + args.map { "      - $it" } +
        listOf(
            "    ports:",
            "      - \"127.0.0.1:{{port.main}}:$port\"",
            "    volumes:",
            "      - \"{{runtime}}:/gantry:ro\"",
            "      - \"{{dir}}/db-log:/db-log\"",
            "      - \"{{dir}}/tls:/tls:ro\"",
            "    depends_on:",
            "      $db:",
            "        condition: service_healthy",
            "    healthcheck:",
            "      test: [\"CMD\", \"bash\", \"-c\", \":> /dev/tcp/127.0.0.1/$port\"]",
            "      interval: 2s",
            "      timeout: 3s",
            "      retries: 30",
            "    restart: unless-stopped",
            "",
        )
}
