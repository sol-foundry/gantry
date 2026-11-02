package dev.gantry.mcp

import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException

/** Connects a workspace's coding agents to one app, or disconnects them. */
class Installer(
    private val engine: Engine,
    private val agents: List<Agent>,
    private val entryFor: (String) -> ServerEntry,
) {
    /** Adds the app's entry to each agent (all of them, or those whose id is in [only]). The app must exist. */
    fun install(
        app: String,
        ws: Workspace,
        only: Set<String>?,
    ): List<Outcome> {
        val chosen = chosen(only)
        engine.readApp(app)
        val entry = entryFor(app)
        return chosen.map { it.install(ws, entry) }
    }

    /** Removes the app's entry. The app itself may already be destroyed. */
    fun uninstall(
        app: String,
        ws: Workspace,
        only: Set<String>?,
    ): List<Outcome> = chosen(only).map { it.uninstall(ws, app) }

    /** Agent name -> apps connected in this workspace, each with what's wrong with its entry, if anything. */
    fun status(ws: Workspace): Map<String, List<AppLink>> =
        agents.associate { agent -> agent.name to agent.entries(ws).toSortedMap().map { (app, entry) -> AppLink(app, staleness(entry)) } }

    private fun chosen(only: Set<String>?): List<Agent> {
        val unknown = only.orEmpty() - agents.map { it.id }.toSet()
        if (unknown.isNotEmpty()) {
            throw GantryException("unknown agent ${unknown.joinToString()}; choose from ${agents.joinToString { it.id }}")
        }
        return if (only == null) agents else agents.filter { it.id in only }
    }
}

/** An app an agent is connected to; [problem] says why its entry would no longer start. */
data class AppLink(
    val app: String,
    val problem: String?,
)
