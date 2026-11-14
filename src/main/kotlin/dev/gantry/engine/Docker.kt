package dev.gantry.engine

import dev.gantry.gateway.authorizerEnvFiles
import dev.gantry.gateway.authorizersStartedWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Why Docker did or didn't answer: decides what to tell the developer. */
enum class DockerState { RUNNING, NOT_FOUND, NOT_ANSWERING, DOWN }

/** What [Docker.execWithInput] did: ran it, found no running container, or could not run it in one that is. */
enum class ExecResult { DONE, NOT_RUNNING, FAILED }

/** Container lifecycle for apps, by driving `docker compose` against each app's generated compose file. */
class Docker(
    private val engine: Engine,
) {
    /** Runs docker compose for [app]. With [stream], output goes straight to the terminal. */
    fun compose(
        app: String,
        vararg args: String,
        stream: Boolean = false,
    ): String {
        engine.loadApp(app)
        return run(composeArgs(app) + args, stream)
    }

    private fun composeArgs(app: String) = listOf("compose", "-p", project(app), "-f", engine.appDir(app).resolve("compose.yml").toString())

    private fun run(
        args: List<String>,
        stream: Boolean = false,
    ): String {
        val builder = ProcessBuilder(listOf(engine.dockerCommand) + args)
        try {
            if (stream) {
                val exit = builder.inheritIO().start().waitFor()
                if (exit != 0) throw GantryException("docker ${args.first()} exited with status $exit")
                return ""
            }
            val process = builder.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) throw GantryException(output.trim().ifEmpty { "docker ${args.first()} failed" })
            return output
        } catch (_: IOException) {
            throw GantryException("docker not found; install Docker Desktop or Colima")
        }
    }

    private fun project(app: String) = "gantry-$app"

    /** How long [probe] and [liveStates] wait for docker: a hung daemon must not hang an agent's tool call. */
    var queryTimeout: Duration = Duration.ofSeconds(10)

    /** How long [execWithInput] lets a command in a container run, stdin included. */
    var execTimeout: Duration = Duration.ofSeconds(30)

    /** Whether the Docker daemon answers, and if not, why. */
    fun probe(): DockerState =
        when (query(listOf("version", "--format", "{{.Server.Version}}"))) {
            is Query.Ok -> DockerState.RUNNING
            is Query.Failed -> DockerState.DOWN
            Query.NotFound -> DockerState.NOT_FOUND
            Query.TimedOut -> DockerState.NOT_ANSWERING
        }

    fun reachable(): Boolean = probe() == DockerState.RUNNING

    private sealed interface Query {
        data class Ok(
            val output: String,
        ) : Query

        data class Failed(
            val output: String,
        ) : Query

        data object NotFound : Query

        data object TimedOut : Query
    }

    /** Runs docker with [queryTimeout], never throwing: output goes to a temp file so a hung process can be abandoned. */
    private fun query(args: List<String>): Query {
        val out = Files.createTempFile("gantry-docker", ".out")
        try {
            val process =
                try {
                    ProcessBuilder(listOf(engine.dockerCommand) + args).redirectErrorStream(true).redirectOutput(out.toFile()).start()
                } catch (_: IOException) {
                    return Query.NotFound
                }
            if (!process.waitFor(queryTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return Query.TimedOut
            }
            val output = Files.readString(out)
            return if (process.exitValue() == 0) Query.Ok(output) else Query.Failed(output.trim())
        } finally {
            Files.deleteIfExists(out)
        }
    }

    /** Whether [container] exists and runs; false when Docker cannot say. */
    fun isRunning(container: String): Boolean =
        (query(listOf("inspect", "--type", "container", "-f", "{{.State.Running}}", container)) as? Query.Ok)?.output?.trim() == "true"

    /** False when the volume is absent or Docker cannot be asked (not installed, daemon down). */
    fun volumeExists(name: String): Boolean = runCatching { run(listOf("volume", "inspect", name)) }.isSuccess

    /** False when the image is absent or Docker cannot be asked (not installed, daemon down). */
    fun imageExists(name: String): Boolean = runCatching { run(listOf("image", "inspect", name)) }.isSuccess

    fun up(
        app: String,
        stream: Boolean = false,
    ) {
        // Re-render first so catalog changes from a newer gantry reach apps created by an older one.
        val current = engine.refresh(app)
        if (current.services.isEmpty()) throw GantryException("no services; add some first")
        val authorizerEnvs = engine.authorizerEnvFiles(app)
        ensureProxyImage(current)
        stopDisplacedDatabases(current)
        // --remove-orphans stops services removed from the app; their ports are already back in the pool.
        compose(app, "up", "-d", "--wait", "--build", "--remove-orphans", stream = stream)
        // `up` recreated every authorizer whose env file changed, so pending ones get their routes back.
        if (engine.authorizersStartedWith(app, authorizerEnvs)) engine.refresh(app)
        // One-shot helpers sit behind a compose profile: `up --wait` treats any exited container as a failure.
        current.services.flatMap { engine.catalog.resolve(it.id).postUp }.forEach {
            runCatching { compose(app, "--profile", INIT_PROFILE, "up", "--force-recreate", it, stream = stream) }
        }
    }

    /** Starts one service and waits for it to be healthy, without post-up hooks or migrations. */
    fun upService(
        app: String,
        service: String,
    ) {
        val current = engine.refresh(app)
        ensureProxyImage(current)
        stopDisplacedDatabases(current)
        compose(app, "up", "-d", "--wait", engine.catalog.resolve(service).id)
    }

    /** Pulls the proxy's image while the old database still runs, so an offline machine never loses its database to the swap. */
    private fun ensureProxyImage(app: App) {
        val proxied = app.services.any { it.id in DATABASE_SERVICES && engine.serviceOptions(app, it.id)["query_log"] == "true" }
        if (!proxied || imageExists(QUERY_LOG_IMAGE)) return
        try {
            run(listOf("pull", QUERY_LOG_IMAGE))
        } catch (e: GantryException) {
            throw GantryException(
                "cannot pull $QUERY_LOG_IMAGE for the query-log proxy, so nothing was stopped: ${e.message?.trim()}. " +
                    "Retry online, or set the database's query_log=false.",
            )
        }
    }

    /**
     * Turning the query log on or off moves a database between `<service>` and `<service>-db`, and compose starts the
     * new one before it replaces or removes the old: both would run on the same data volume. The old one is stopped
     * first. Best effort: with no such container there is nothing to stop.
     */
    private fun stopDisplacedDatabases(app: App) {
        app.services.filter { it.id in DATABASE_SERVICES }.forEach { state ->
            val proxied = engine.serviceOptions(app, state.id)["query_log"] == "true"
            val name = "${app.name}-${databaseServiceName(state.id, !proxied)}"
            val image =
                runCatching { run(listOf("inspect", "--type", "container", "-f", "{{.Config.Image}}", name)).trim() }.getOrNull()
                    ?: return@forEach
            // Only the database's own image: a proxy on any JRE tag must keep running.
            if (!image.startsWith("${state.id}:")) return@forEach
            runCatching { run(listOf("stop", name)) }
        }
    }

    /**
     * Pipes [input] into [command] inside [container], e.g. to rewrite a file it holds open: a write made through a
     * bind mount from the host side (virtiofs) leaves the container's append offset stale. [ExecResult.NOT_RUNNING]
     * when the container is absent or stopped, so nothing in it can be writing.
     */
    fun execWithInput(
        container: String,
        command: List<String>,
        input: ByteArray,
    ): ExecResult {
        when (val state = query(listOf("inspect", "--type", "container", "-f", "{{.State.Running}}", container))) {
            is Query.Ok -> if (state.output.trim() != "true") return ExecResult.NOT_RUNNING
            // Only a missing container means nothing writes; a daemon or permission error must not look like one.
            is Query.Failed -> return if ("no such" in state.output.lowercase()) ExecResult.NOT_RUNNING else ExecResult.FAILED
            Query.NotFound -> return ExecResult.NOT_RUNNING
            Query.TimedOut -> return ExecResult.FAILED
        }
        val process =
            try {
                ProcessBuilder(listOf(engine.dockerCommand, "exec", "-i", container) + command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (_: IOException) {
                return ExecResult.FAILED
            }
        // Fed from its own thread: a stalled exec stops reading stdin, and the timeout must still fire.
        val feeder =
            Thread {
                runCatching { process.outputStream.use { it.write(input) } }
            }.apply {
                isDaemon = true
                start()
            }
        val finished = process.waitFor(execTimeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            // Children first: destroying closes stdin, which blocks while any of them still holds the pipe.
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        feeder.join(TimeUnit.SECONDS.toMillis(1))
        return if (finished && process.exitValue() == 0) ExecResult.DONE else ExecResult.FAILED
    }

    /** Best effort: a missing container, or no Docker at all, is not an error. */
    fun removeContainer(name: String) {
        runCatching { run(listOf("rm", "-f", name)) }
    }

    /** Deletes [volume], after force-removing [container] in case a stopped one still holds it. */
    fun removeVolume(
        volume: String,
        container: String,
    ) {
        removeContainer(container)
        run(listOf("volume", "rm", volume))
    }

    /**
     * `docker run --rm` with [args], streaming merged output lines to [onLine]; returns the exit code. [env] is set on the
     * docker process, so `-e NAME` in [args] passes a value to the container without it appearing on any command line.
     */
    fun runContainer(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit,
    ): Int = streamLines(listOf("run", "--rm") + args, env, onLine)

    /** `docker build -t [tag] [dir]`, streaming output lines to [onLine]; returns the exit code. */
    fun buildImage(
        tag: String,
        dir: Path,
        onLine: (String) -> Unit,
    ): Int = streamLines(listOf("build", "-t", tag, dir.toAbsolutePath().toString()), emptyMap(), onLine)

    /** Runs docker compose for [app], streaming output lines to [onLine]; returns the exit code. */
    fun composeLines(
        app: String,
        args: List<String>,
        onLine: (String) -> Unit,
    ): Int {
        engine.loadApp(app)
        return streamLines(composeArgs(app) + args, emptyMap(), onLine)
    }

    /**
     * Runs docker with [args], streaming merged output lines to [onLine]; returns the exit code. If [onLine] throws,
     * the process is stopped and the exception rethrown once it has exited.
     */
    private fun streamLines(
        args: List<String>,
        env: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int {
        val builder = ProcessBuilder(listOf(engine.dockerCommand) + args).redirectErrorStream(true)
        builder.environment().putAll(env)
        val process =
            try {
                builder.start()
            } catch (_: IOException) {
                throw GantryException("docker not found; install Docker Desktop or Colima")
            }
        var failure: Throwable? = null
        try {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (failure == null) {
                            try {
                                onLine(line)
                            } catch (e: Throwable) {
                                // Stop the process rather than let it run on unobserved; keep draining so it can exit.
                                failure = e
                                process.destroy()
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // destroy() closes the output stream, which ends the drain early; only a read error of its own counts.
                if (failure == null) throw e
            }
            val exit = process.waitFor()
            failure?.let { throw it }
            return exit
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    fun down(
        app: String,
        stream: Boolean = false,
    ) {
        // Without the profile, `down` leaves init containers behind attached to the removed network.
        compose(app, "--profile", INIT_PROFILE, "down", "--remove-orphans", stream = stream)
    }

    /**
     * Removes containers and volumes (best effort), the app's ports and its files. `down -v` only covers
     * volumes still in the compose file, so volumes of services removed earlier are deleted by project label.
     */
    @Synchronized
    fun destroy(
        app: String,
        stream: Boolean = false,
    ) {
        runCatching { compose(app, "--profile", INIT_PROFILE, "down", "-v", "--remove-orphans", stream = stream) }
        runCatching {
            val leftover =
                run(
                    listOf("volume", "ls", "-q", "--filter", "label=com.docker.compose.project=${project(app)}"),
                ).lines().filter { it.isNotBlank() }
            if (leftover.isNotEmpty()) run(listOf("volume", "rm") + leftover)
        }
        engine.updatePorts { ports -> ports.keys.removeIf { it.startsWith("$app/") } }
        runCatching { engine.secrets.forget(app) }
        engine.appDir(app).toFile().deleteRecursively()
    }

    fun logs(
        app: String,
        service: String?,
        tail: Int,
        follow: Boolean = false,
        stream: Boolean = false,
    ): String {
        val args = mutableListOf("logs", "--no-color", "--tail", tail.toString())
        if (follow) args += "-f"
        if (service != null) args += logServices(app, service)
        return compose(app, *args.toTypedArray(), stream = stream)
    }

    /** A query-logged database's logs are its own and its proxy's, each line labelled; `<service>-db` names the database alone. */
    private fun logServices(
        app: String,
        service: String,
    ): List<String> {
        val base = service.removeSuffix("-db")
        if (base != service && base in DATABASE_SERVICES) return listOf(service)
        val id = engine.catalog.resolve(service).id
        if (id !in DATABASE_SERVICES) return listOf(id)
        val loaded = engine.readApp(app)
        val proxied = loaded.service(id) != null && engine.serviceOptions(loaded, id)["query_log"] == "true"
        return if (proxied) listOf(databaseServiceName(id, true), id) else listOf(id)
    }

    fun status(appName: String): AppStatus {
        val app = engine.loadApp(appName)
        val live = containerStates(appName)
        return AppStatus(
            name = appName,
            services =
                app.services.map { state ->
                    val def = engine.catalog.resolve(state.id)
                    ServiceStatus(
                        id = state.id,
                        description = def.description,
                        options = engine.serviceOptions(app, state.id),
                        ports = state.ports,
                        state = live[state.id]?.first?.ifEmpty { null } ?: "not created",
                        health = live[state.id]?.second ?: "",
                        ui = uiLink(app, state),
                    )
                },
        )
    }

    /** App name -> its running services, from one `docker ps` across every gantry project. */
    fun runningServices(): Map<String, Set<String>> {
        val format = "{{.Label \"com.docker.compose.project\"}}\t{{.Label \"com.docker.compose.service\"}}"
        val output = runCatching { run(listOf("ps", "--filter", "label=com.docker.compose.project", "--format", format)) }.getOrDefault("")
        return output
            .lineSequence()
            .map { it.split('\t') }
            .filter { it.size == 2 && it[0].startsWith("gantry-") }
            .groupBy({ it[0].removePrefix("gantry-") }, { it[1] })
            .mapValues { it.value.toSet() }
    }

    /**
     * Service id -> (state, health) of [app]'s containers, read without loading (and so without upgrading) the app.
     * Null when `docker compose` couldn't answer, so callers don't mistake that for stopped containers.
     */
    fun liveStates(app: App): Map<String, Pair<String, String>>? =
        when (val result = query(composeArgs(app.name) + listOf("ps", "-a", "--format", "json"))) {
            is Query.Ok -> parseStates(result.output)
            else -> null
        }

    private fun containerStates(app: String): Map<String, Pair<String, String>> =
        parseStates(runCatching { compose(app, "ps", "-a", "--format", "json") }.getOrDefault(""))

    private fun parseStates(output: String): Map<String, Pair<String, String>> =
        foldDatabases(
            output
                .lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                }.mapNotNull { obj ->
                    obj.text("Service")?.let { it to ((obj.text("State") ?: "") to (obj.text("Health") ?: "")) }
                }.toMap(),
        )

    /** A database behind the query-log proxy reads as the worse of the proxy and its `<service>-db` container. */
    private fun foldDatabases(states: Map<String, Pair<String, String>>): Map<String, Pair<String, String>> {
        val out = states.toMutableMap()
        DATABASE_SERVICES.forEach { id ->
            val proxy = states[id] ?: return@forEach
            val db = states[databaseServiceName(id, true)] ?: return@forEach
            val state = if (db.first != "running") db.first else proxy.first
            val health = listOf(db.second, proxy.second).minBy { HEALTH_RANK.indexOf(it).let { i -> if (i < 0) HEALTH_RANK.size else i } }
            out[id] = state to health
        }
        return out
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    /** Where the service's browser UI is: its own (from the allocated ports), a built-in explorer, or a hint. */
    fun uiLink(
        app: App,
        state: ServiceState,
    ): UiLink? {
        val ui = engine.catalog.resolve(state.id).ui ?: return null
        return when {
            ui.external != null -> UiLink("external", url = Template.render(ui.external, portContext(state)))
            ui.via != null -> {
                val via =
                    app.service(ui.via)
                        ?: return UiLink("hint", note = "add '${ui.via}' for a browser UI")
                uiLink(app, via)?.copy(note = "opens ${via.id}")
            }
            else -> UiLink("builtin", name = ui.builtin)
        }
    }

    private fun portContext(state: ServiceState): Context = state.ports.mapKeys { "port.${it.key}" }.mapValues { it.value.toString() }

    private companion object {
        const val INIT_PROFILE = "init"

        /** Worst first; anything else (no healthcheck) ranks after them. */
        val HEALTH_RANK = listOf("unhealthy", "starting", "healthy")
    }
}
