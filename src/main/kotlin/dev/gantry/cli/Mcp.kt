package dev.gantry.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.mcp.GitRepo
import dev.gantry.mcp.Installer
import dev.gantry.mcp.McpServer
import dev.gantry.mcp.Outcome
import dev.gantry.mcp.Workspace
import dev.gantry.mcp.agents.ClaudeAgent
import dev.gantry.mcp.agents.CodexAgent
import dev.gantry.mcp.agents.CursorAgent
import dev.gantry.mcp.locateLauncher
import dev.gantry.mcp.managedLauncher
import dev.gantry.mcp.resolveDocker
import dev.gantry.mcp.serverEntry
import java.io.OutputStreamWriter
import java.nio.file.Path

private val AGENT_IDS = arrayOf("claude", "cursor", "codex")

internal class Mcp(
    engine: Engine,
) : CliktCommand(name = "mcp") {
    init {
        subcommands(McpInstall(engine), McpUninstall(engine), McpStatus(engine), McpServe(engine))
    }

    override fun help(context: Context) =
        "Connect this repo's coding agents (Claude Code, Cursor, Codex) to an app, so they can look up its connection details."

    override fun run() = Unit
}

private fun installer(engine: Engine) =
    Installer(engine, listOf(ClaudeAgent(), CursorAgent(), CodexAgent())) { app ->
        val launcher =
            locateLauncher()
                ?: throw GantryException(
                    "can't find this Gantry install's launcher; run gantry from an installed distribution (./gradlew installDist)",
                )
        // A managed wrapper picks its own Java; recording this one's versioned path would break on upgrade.
        val javaHome = if (managedLauncher() != null) null else System.getProperty("java.home")
        serverEntry(app, launcher, javaHome, docker = resolveDocker(engine.dockerCommand))
    }

/** The repo containing [dir] (default: the current directory); agent config is keyed on its root. */
private fun workspace(dir: Path?): Workspace {
    val start = (dir ?: Path.of("")).toAbsolutePath().normalize()
    val repo = GitRepo.find(start)
    return Workspace(
        if (dir ==
            null
        ) {
            repo?.root ?: start
        } else {
            start.toRealPath()
        },
        repo,
        gitMissing = repo == null && GitRepo.looksLikeRepo(start),
    )
}

private fun CliktCommand.report(outcomes: List<Outcome>) {
    outcomes.forEach { outcome ->
        val line =
            when (outcome) {
                is Outcome.Installed -> "✓ ${outcome.agent.padEnd(
                    12,
                )} ${if (outcome.updated) "updated" else "installed"} (${outcome.where})"
                is Outcome.Removed -> "✓ ${outcome.agent.padEnd(12)} removed (${outcome.where})"
                is Outcome.Skipped -> "– ${outcome.agent.padEnd(12)} skipped: ${outcome.reason}"
            }
        echo(line)
    }
    outcomes.filterIsInstance<Outcome.Installed>().mapNotNull { o -> o.note?.let { "${o.agent}: $it" } }.forEach { echo("  $it") }
}

private class McpInstall(
    private val engine: Engine,
) : CliktCommand(name = "install") {
    private val app by argument()
    private val agents by option("--agent", help = "Only this agent (repeatable)").choice(*AGENT_IDS).multiple()
    private val dir by option(
        "--dir",
        help = "Project directory (default: the git repo you're in)",
    ).path(mustExist = true, canBeFile = false)

    override fun help(context: Context) =
        "Connect this repo's coding agents to APP. Personal to this machine: nothing is committed and no password is written."

    override fun run() {
        val ws = workspace(dir)
        // Installing first: a missing app fails before anything claims to be connecting.
        val outcomes = installer(engine).install(app, ws, agents.toSet().ifEmpty { null })
        echo("Connecting ${ws.dir} to app '$app'")
        report(outcomes)
    }
}

private class McpUninstall(
    private val engine: Engine,
) : CliktCommand(name = "uninstall") {
    private val app by argument()
    private val agents by option("--agent", help = "Only this agent (repeatable)").choice(*AGENT_IDS).multiple()
    private val dir by option(
        "--dir",
        help = "Project directory (default: the git repo you're in)",
    ).path(mustExist = true, canBeFile = false)

    override fun help(context: Context) = "Disconnect this repo's coding agents from APP, removing only what install added."

    override fun run() = report(installer(engine).uninstall(app, workspace(dir), agents.toSet().ifEmpty { null }))
}

private class McpStatus(
    private val engine: Engine,
) : CliktCommand(name = "status") {
    private val dir by option(
        "--dir",
        help = "Project directory (default: the git repo you're in)",
    ).path(mustExist = true, canBeFile = false)

    override fun help(context: Context) = "Show which apps this repo's coding agents are connected to."

    override fun run() {
        val ws = workspace(dir)
        echo(ws.dir.toString())
        installer(engine).status(ws).forEach { (agent, links) ->
            echo("  ${agent.padEnd(12)} ${links.joinToString { it.app }.ifEmpty { "–" }}")
            links.forEach { link ->
                link.problem?.let { echo("    ${link.app}: $it; re-run `gantry mcp install ${link.app}`") }
            }
        }
    }
}

/** Started by agents, not people: speaks MCP on stdin/stdout, so nothing else may print to stdout. */
private class McpServe(
    private val engine: Engine,
) : CliktCommand(name = "serve") {
    private val app by argument()

    override val hiddenFromHelp = true

    override fun help(context: Context) = "Serve APP's connection details over MCP on stdio (started by coding agents)."

    override fun run() {
        val version = Engine::class.java.`package`?.implementationVersion ?: "dev"
        McpServer(engine, app, version).serve(System.`in`.bufferedReader(), OutputStreamWriter(System.out, Charsets.UTF_8))
    }
}
