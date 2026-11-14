package dev.gantry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.gateway.Authorizers
import dev.gantry.gateway.Call
import dev.gantry.gateway.CallLog
import dev.gantry.gateway.GATEWAY
import dev.gantry.gateway.SocketEvent
import dev.gantry.gateway.authorizersUsing
import dev.gantry.gateway.callLog
import dev.gantry.gateway.configText
import dev.gantry.gateway.gatewayConfig
import dev.gantry.gateway.saveGatewayConfig
import dev.gantry.gateway.secretNames
import dev.gantry.gateway.socketStatus
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private const val DEFAULT_CALLS = 20
private const val FOLLOW_WINDOW = 200
private const val FOLLOW_INTERVAL_MS = 1000L
private val secretNameRule = Regex("[^}\\s]+")
private val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

internal class GatewayCommand(
    engine: Engine,
    authorizers: Authorizers = Authorizers(engine),
) : CliktCommand(name = "gateway") {
    init {
        subcommands(
            GatewayCalls(engine),
            GatewayClear(engine),
            GatewaySockets(engine),
            GatewayConfigCommand(engine, authorizers),
            GatewayReload(engine, authorizers),
        )
    }

    override fun help(context: Context) =
        "Inspect an app's API gateway: its recent REST calls, its open WebSocket connections and their events, and " +
            "its gateway.yml, and apply a hand edit."

    override fun run() = Unit
}

private fun requireGateway(
    engine: Engine,
    app: String,
) {
    if (engine.loadApp(app).service(GATEWAY) == null) throw GantryException("'$app' has no gateway (run `gantry add $app gateway`)")
}

private class GatewayCalls(
    private val engine: Engine,
) : CliktCommand(name = "calls") {
    override fun help(context: Context) = "Show the gateway's recent calls, oldest first."

    private val app by argument()
    private val limit by option("--limit", help = "How many calls to show.").int().restrictTo(min = 1).default(DEFAULT_CALLS)
    private val status by option(
        "--status",
        metavar = "STATUS",
        help = "Only calls with this status: a class like 4xx, or a code like 404.",
    )
    private val path by option("--path", metavar = "GLOB", help = "Only calls whose path matches, e.g. /api/** (* is one segment).")
    private val follow by option("--follow", "-f", help = "Keep printing new calls until interrupted.").flag()

    override fun run() {
        requireGateway(engine, app)
        val log = engine.callLog(app)
        echo(callHeader())
        if (!follow) {
            log.recent(limit, status, path).asReversed().forEach { echo(callRow(it)) }
            return
        }
        val follower = CallFollower(log, status, path)
        follower.poll().takeLast(limit).forEach { echo(callRow(it)) }
        while (true) {
            Thread.sleep(FOLLOW_INTERVAL_MS)
            follower.poll().forEach { echo(callRow(it)) }
        }
    }
}

private class GatewayClear(
    private val engine: Engine,
) : CliktCommand(name = "clear") {
    override fun help(context: Context) = "Delete the gateway's recorded calls, so the call log starts over."

    private val app by argument()

    override fun run() {
        requireGateway(engine, app)
        engine.callLog(app).clear()
        echo("Cleared the call log of '$app'")
    }
}

private class GatewaySockets(
    private val engine: Engine,
) : CliktCommand(name = "sockets") {
    override fun help(context: Context) = "Show the gateway's open WebSocket connections and its recent socket events, oldest first."

    private val app by argument()
    private val limit by option("--limit", help = "How many events to show.").int().restrictTo(1..500).default(DEFAULT_CALLS)

    override fun run() {
        requireGateway(engine, app)
        val status = engine.socketStatus(app)
        if (!status.running) throw GantryException("the WebSocket relay of '$app' does not answer; run `gantry start $app`")
        echo("${status.connections.size} open connection(s)")
        status.connections.forEach { c -> echo("  ${c.connectionId}  user=${c.userId ?: "-"}  since=${c.since ?: "-"}") }
        echo("")
        echo("%-8s  %-10s  %s".format("TIME", "EVENT", "DETAIL"))
        status.events.take(limit).asReversed().forEach { e ->
            val time = runCatching { clock.format(Instant.parse(e.time)) }.getOrDefault(e.time)
            echo("%-8s  %-10s  %s".format(time, e.type, socketDetail(e)))
        }
    }
}

private fun socketDetail(e: SocketEvent): String =
    when (e.type) {
        "post" -> "${e.connectionId}: ${e.bytes} bytes pushed"
        "gone" -> "${e.connectionId}: push to a closed connection (410)"
        "message" -> "${e.connectionId} -> ${e.route ?: "no route"} (${e.status})"
        "rejected" -> "${e.connectionId} user=${e.userId ?: "-"}: \$connect answered ${e.status}"
        "disconnect" -> "${e.connectionId} (${e.reason})"
        "\$disconnect" -> "${e.connectionId}: \$disconnect answered ${e.status}"
        else -> "${e.connectionId} user=${e.userId ?: "-"} ${e.path ?: ""}".trimEnd()
    }

/** Polls a call log for calls it has not returned before, oldest first; calls are told apart by id. */
internal class CallFollower(
    private val log: CallLog,
    private val status: String?,
    private val path: String?,
) {
    private var seen = emptySet<String>()

    fun poll(): List<Call> {
        val window = log.recent(FOLLOW_WINDOW, status, path)
        val fresh = window.filter { it.id !in seen }.asReversed()
        // Only ids still in the window can come back, so the set stays as small as the window.
        seen = window.map { it.id }.toSet()
        return fresh
    }
}

private fun callHeader() = "TIME      METHOD  STATUS      MS  USER                  PATH"

private fun callRow(call: Call): String {
    val time =
        try {
            clock.format(Instant.parse(call.time))
        } catch (_: DateTimeParseException) {
            "-"
        }
    val user = call.userId ?: "-"
    return "${time.padEnd(9)} ${call.method.padEnd(7)} ${call.status.toString().padEnd(6)} ${call.latencyMs.toString().padStart(5)}  " +
        "${user.padEnd(21)} ${call.uri}"
}

private class GatewayConfigCommand(
    private val engine: Engine,
    private val authorizers: Authorizers,
) : CliktCommand(name = "config") {
    override fun help(context: Context) =
        """
        Print an app's gateway.yml, or check a file and save it as the app's gateway.yml.

        A running gateway picks up a saved file without a restart; authorizers whose env changed are restarted.
        """.trimIndent()

    private val app by argument()
    private val file by option("--file", metavar = "PATH", help = "Check this file and, when it is valid, save it as gateway.yml.")

    override fun run() {
        requireGateway(engine, app)
        val path = file
        if (path == null) {
            echo(configText(engine.appDir(app)), trailingNewline = false)
            return
        }
        val text =
            try {
                Files.readString(Path.of(path))
            } catch (_: NoSuchFileException) {
                throw GantryException("$path does not exist")
            }
        apply(engine, authorizers, app, text, "error: $path is not valid; gateway.yml was not changed:", "saved gateway.yml of '$app'")
    }
}

private class GatewayReload(
    private val engine: Engine,
    private val authorizers: Authorizers,
) : CliktCommand(name = "reload") {
    override fun help(context: Context) =
        """
        Check the app's gateway.yml and apply it: use after editing the file by hand.

        The gateway keeps running what it last applied until then; nothing is applied when the file has problems.
        """.trimIndent()

    private val app by argument()

    override fun run() {
        requireGateway(engine, app)
        val file = engine.appDir(app).resolve("gateway.yml")
        val refused = "error: $file is not valid; the gateway still runs the last applied one:"
        apply(engine, authorizers, app, configText(engine.appDir(app)), refused, "applied gateway.yml of '$app'")
    }
}

/**
 * Saves [text] as the app's gateway.yml and re-renders, then says [done] and restarts the authorizers whose env
 * changed; on problems says [refused], lists them and exits 1.
 */
private fun CliktCommand.apply(
    engine: Engine,
    authorizers: Authorizers,
    app: String,
    text: String,
    refused: String,
    done: String,
) {
    val problems = engine.saveGatewayConfig(app, text)
    if (problems.isNotEmpty()) {
        echo(refused, err = true)
        problems.forEach { echo("  $it", err = true) }
        throw ProgramResult(1)
    }
    echo(done)
    authorizers.recreatePending(app) { echo(it) }.forEach { echo("restarted auth-$it with its new env") }
}

internal class AuthorizerCommand(
    engine: Engine,
    authorizers: Authorizers = Authorizers(engine),
) : CliktCommand(name = "authorizer") {
    init {
        subcommands(
            AuthorizerInit(authorizers),
            AuthorizerPush(authorizers),
            AuthorizerList(authorizers),
            AuthorizerRemove(engine, authorizers),
        )
    }

    override fun help(context: Context) =
        "Write, build and run custom authorizers: your own code that the gateway asks whether a request may pass."

    override fun run() = Unit
}

private class AuthorizerInit(
    private val authorizers: Authorizers,
) : CliktCommand(name = "init") {
    override fun help(context: Context) = "Start a custom authorizer from a template."

    private val name by argument()
    private val lang by option("--lang", metavar = "LANG", help = "node, python or go.").required()
    private val dir by option("--dir", metavar = "PATH", help = "Where to write it (default ./<name>).")

    override fun run() {
        val target = Path.of(dir ?: name)
        authorizers.init(name, lang, target)
        echo("wrote a $lang authorizer to $target; build and run it with: gantry authorizer push <app> $name $target")
    }
}

private class AuthorizerPush(
    private val authorizers: Authorizers,
) : CliktCommand(name = "push") {
    override fun help(context: Context) =
        "Build an authorizer's directory (it needs a Dockerfile) and run it next to the app's gateway; run again after changes."

    private val app by argument()
    private val name by argument()
    private val dir by argument()

    override fun run() {
        authorizers.push(app, name, Path.of(dir)) { echo(it) }
        echo("pushed authorizer '$name'; use it in gateway.yml as type: custom, container: $name")
    }
}

private class AuthorizerList(
    private val authorizers: Authorizers,
) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List an app's pushed authorizers."

    private val app by argument()

    override fun run() {
        val pushed = authorizers.list(app)
        if (pushed.isEmpty()) echo("no authorizers; run `gantry authorizer init <name> --lang node`")
        pushed.forEach { echo("${it.name.padEnd(20)} ${it.port.toString().padEnd(6)} ${it.dir}") }
    }
}

private class AuthorizerRemove(
    private val engine: Engine,
    private val authorizers: Authorizers,
) : CliktCommand(name = "remove") {
    override fun help(context: Context) = "Stop and forget an authorizer; its image is kept."

    private val app by argument()
    private val name by argument()

    override fun run() {
        authorizers.remove(app, name)
        echo("removed authorizer '$name'")
        val using = runCatching { engine.gatewayConfig(app).authorizersUsing(name) }.getOrDefault(emptyList())
        if (using.isNotEmpty()) {
            echo(
                "warning: gateway.yml still uses '$name' (authorizers: ${using.joinToString()}); requests that need it will " +
                    "fail until you push it again or change gateway.yml",
                err = true,
            )
        }
    }
}

/** [stdin] reads all of standard input when it is not a terminal. */
internal class SecretCommand(
    engine: Engine,
    authorizers: Authorizers = Authorizers(engine),
    stdin: () -> String = { System.`in`.readBytes().decodeToString() },
) : CliktCommand(name = "secret") {
    init {
        subcommands(SecretSet(engine, authorizers, stdin), SecretList(engine), SecretRemove(engine, authorizers))
    }

    override fun help(context: Context) =
        "Set the secrets an app's gateway.yml refers to as {{secret:NAME}}. Values stay on this machine, never in exports."

    override fun run() = Unit
}

private class SecretSet(
    private val engine: Engine,
    private val authorizers: Authorizers,
    private val stdin: () -> String,
) : CliktCommand(name = "set") {
    override fun help(context: Context) =
        """
        Set a secret, reading its value from standard input (never from the command line).

        Typed at a terminal, the value is not shown. Piped, all of standard input is used, less one trailing newline:

          printf %s "${'$'}KEY" | gantry secret set my-app INTERNAL_API_KEY
        """.trimIndent()

    private val app by argument()
    private val name by argument()

    override fun run() {
        checkSecretName(name)
        engine.loadApp(app)
        val value = readValue()
        if (value.isEmpty()) throw GantryException("the value is empty; nothing was set")
        engine.secrets.setSecret(app, name, value)
        refreshAfter("saved secret $name of '$app'", engine, app)
        echo("set secret $name of '$app'")
        authorizers.recreatePending(app) { echo(it) }.forEach { echo("restarted auth-$it with the new value") }
    }

    private fun readValue(): String {
        val console = System.console()
        if (terminal.terminalInfo.inputInteractive && console != null) {
            return console.readPassword("value for $name: ")?.concatToString().orEmpty()
        }
        val text = stdin()
        return text.removeSuffix("\n").let { if (it.length < text.length) it.removeSuffix("\r") else it }
    }
}

private class SecretList(
    private val engine: Engine,
) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List an app's secret names (never their values), and those gateway.yml uses but are not set."

    private val app by argument()

    override fun run() {
        engine.loadApp(app)
        val set = engine.secrets.secretNames(app)
        val used =
            if (engine.loadApp(app).service(GATEWAY) == null) {
                emptySet()
            } else {
                runCatching { engine.gatewayConfig(app).secretNames() }.getOrDefault(emptySet())
            }
        if (set.isEmpty() && used.isEmpty()) echo("no secrets; set one with: gantry secret set $app <name>")
        (set + used).toSortedSet().forEach { name ->
            val note =
                when {
                    name !in set -> "not set; run: gantry secret set $app $name"
                    name !in used -> "set, not used by gateway.yml"
                    else -> "set"
                }
            echo("${name.padEnd(30)} $note")
        }
    }
}

private class SecretRemove(
    private val engine: Engine,
    private val authorizers: Authorizers,
) : CliktCommand(name = "remove") {
    override fun help(context: Context) = "Forget a secret."

    private val app by argument()
    private val name by argument()

    override fun run() {
        engine.loadApp(app)
        if (!engine.secrets.forgetSecret(app, name)) throw GantryException("'$app' has no secret '$name'")
        refreshAfter("removed secret $name of '$app'", engine, app)
        echo("removed secret $name of '$app'")
        authorizers.recreatePending(app) { echo(it) }.forEach { echo("restarted auth-$it without it") }
    }
}

/** Regenerates the app's files after a secret change that is already [done]; a failure says the change itself was kept. */
private fun CliktCommand.refreshAfter(
    done: String,
    engine: Engine,
    app: String,
) {
    try {
        engine.refresh(app)
    } catch (e: GantryException) {
        echo("error: $done, but the app could not be refreshed: ${e.message}", err = true)
        throw ProgramResult(1)
    }
}

private fun checkSecretName(name: String) {
    if (!secretNameRule.matches(name)) throw GantryException("invalid secret name '$name': no spaces or '}'")
}
