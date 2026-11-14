package dev.gantry.mcp

import dev.gantry.engine.DIRECT_PORT
import dev.gantry.engine.DockerState
import dev.gantry.engine.Engine
import dev.gantry.engine.parseQueues
import dev.gantry.engine.parseTopics
import kotlinx.serialization.Serializable

/** What an agent needs to reach an app's services itself: where they are, how to log in, and what's in them. */
@Serializable
data class ConnectionDetails(
    val app: String,
    /** "reachable" or "unreachable": whether this process could ask Docker for the containers' states. */
    val docker: String,
    /** What to do next when something is down. */
    val hint: String? = null,
    val services: List<ServiceConnection>,
    /** The app's env block, exactly as `gantry env` prints it. */
    val env: String,
)

@Serializable
data class ServiceConnection(
    val id: String,
    val description: String,
    /** The container's state, "not created", or "unknown" when Docker can't be reached. */
    val state: String,
    val health: String,
    /** Where the service listens on this machine; services bind to the loopback interface only. */
    val host: String,
    /** Host ports by name (`main`, `ui`, ...); always given, even when the app omits the env variables. */
    val ports: Map<String, Int>,
    /** This service's variables under the names the app uses; omitted variables are left out. */
    val env: Map<String, String>,
    /**
     * Every connection value under the catalog's own keys (`DB_NAME`, `DB_PASSWORD`, `TEMPORAL_NAMESPACE`, ...),
     * including those the app leaves out of its env, so the agent never has to guess the database or password.
     */
    val connection: Map<String, String>,
    /** Catalog keys the app leaves out of its env (their values are still in [connection]). */
    val omitted: List<String> = emptyList(),
    val queues: List<QueueInfo> = emptyList(),
    val topics: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class QueueInfo(
    val name: String,
    val deadLetterQueue: String,
)

private const val SQS_NOTE = "SQS-compatible: any AWS access key, secret and region work."

/**
 * The live connection details of [appName]. Read-only: it never writes Gantry state, since agents call it while the
 * app is being started or edited elsewhere. Sync sources are deliberately left out: they point at remote databases.
 */
fun connectionDetails(
    engine: Engine,
    appName: String,
): ConnectionDetails {
    val app = engine.readApp(appName)
    val dockerState = engine.docker.probe()
    val reachable = dockerState == DockerState.RUNNING
    val live = if (reachable) engine.docker.liveStates(app) else null
    val entries = engine.envEntries(app)
    val env = entries.filter { !it.omitted }.groupBy({ it.service }, { it.name to it.value })
    val connection = entries.groupBy({ it.service }, { it.key to it.value })
    val services =
        app.services.map { state ->
            val options = engine.serviceOptions(app, state.id)
            ServiceConnection(
                id = state.id,
                description =
                    engine.catalog
                        .resolve(state.id)
                        .description
                        .substringBefore(". "),
                state = if (live == null) "unknown" else live[state.id]?.first?.ifEmpty { null } ?: "not created",
                health = live?.get(state.id)?.second ?: "",
                host = "localhost",
                // The database's direct port bypasses the query log; agents use main like the app.
                ports = state.ports - DIRECT_PORT,
                env = env[state.id].orEmpty().toMap(),
                connection = connection[state.id].orEmpty().toMap(),
                omitted = entries.filter { it.service == state.id && it.omitted }.map { it.key },
                queues =
                    if (state.id ==
                        "elasticmq"
                    ) {
                        parseQueues(options["queues"].orEmpty()).map { QueueInfo(it.name, it.deadLetterQueue) }
                    } else {
                        emptyList()
                    },
                topics =
                    if (state.id ==
                        "kafka"
                    ) {
                        parseTopics(options["topics"].orEmpty(), options["partitions"] ?: "1").map { it.name }
                    } else {
                        emptyList()
                    },
                notes = if (state.id == "elasticmq") listOf(SQS_NOTE) else emptyList(),
            )
        }
    val hint =
        when {
            dockerState == DockerState.NOT_FOUND ->
                "Can't run docker ('${engine.dockerCommand}'), so service states are unknown. Docker may have moved since " +
                    "this agent was connected: re-run `gantry mcp install ${app.name}` in a terminal where docker works."
            dockerState == DockerState.NOT_ANSWERING ->
                "Docker didn't answer in time, so service states are unknown. Is it overloaded or stuck?"
            dockerState == DockerState.DOWN -> "Can't reach the Docker daemon, so service states are unknown. Is Docker running?"
            live == null ->
                "`docker compose` couldn't list the app's containers, so service states are unknown. Check `docker compose version`."
            services.none { it.state == "running" } -> "The app is not running. Start it with: gantry start ${app.name}"
            else -> null
        }
    return ConnectionDetails(app.name, if (reachable) "reachable" else "unreachable", hint, services, engine.envText(app))
}

/** A short plain-text rendering for agents that read the text content rather than the structured result. */
fun ConnectionDetails.toText(): String =
    buildString {
        appendLine("Gantry app '$app' (Docker $docker)")
        hint?.let { appendLine(it) }
        services.forEach { service ->
            appendLine()
            appendLine("${service.id}: ${service.state}${service.health.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()}")
            appendLine("  host ${service.host}, ports " + service.ports.entries.joinToString { "${it.key} ${it.value}" })
            service.env.forEach { (name, value) -> appendLine("  $name=$value") }
            val leftOut = service.omitted.mapNotNull { key -> service.connection[key]?.let { "$key=$it" } }
            if (leftOut.isNotEmpty()) appendLine("  not in the app's env: " + leftOut.joinToString())
            if (service.queues.isNotEmpty()) {
                appendLine("  queues: " + service.queues.joinToString { "${it.name} (dead letters: ${it.deadLetterQueue})" })
            }
            if (service.topics.isNotEmpty()) appendLine("  topics: " + service.topics.joinToString())
            service.notes.forEach { appendLine("  note: $it") }
        }
    }
