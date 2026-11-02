package dev.gantry.mcp

import java.nio.file.Path

fun entry(
    app: String,
    command: String = "/g/bin/gantry",
) = ServerEntry(entryName(app), command, listOf("mcp", "serve", app), mapOf("JAVA_HOME" to "/jdk"))

fun workspace(root: Path) = Workspace(root, GitRepo.find(root))
