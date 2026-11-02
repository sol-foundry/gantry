package dev.gantry.mcp

import dev.gantry.engine.Engine
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** One agent-config entry: how an agent starts `gantry mcp serve <app>`. Holds no ports, passwords or env values. */
data class ServerEntry(
    val name: String,
    val command: String,
    val args: List<String>,
    val env: Map<String, String>,
)

/** Docker settings passed through to the server when set, so it talks to the same daemon the developer uses. */
private val DOCKER_SETTINGS = listOf("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY")

fun entryName(app: String) = "gantry-$app"

/**
 * The entry for [app]. Agents may be started from a GUI without the developer's shell, so it carries what the server
 * needs to start on its own: the launcher's absolute path, the Java to run it with, Gantry's home, and Docker's location.
 */
fun serverEntry(
    app: String,
    launcher: Path,
    /** Null when [launcher] is a managed wrapper that picks its own Java (install.sh, Homebrew). */
    javaHome: String?,
    docker: String?,
    env: Map<String, String> = System.getenv(),
): ServerEntry {
    val vars = linkedMapOf<String, String>()
    javaHome?.let { vars["JAVA_HOME"] = it }
    env["GANTRY_HOME"]?.takeIf { it.isNotBlank() }?.let { vars["GANTRY_HOME"] = it }
    docker?.let { vars["GANTRY_DOCKER"] = it }
    DOCKER_SETTINGS.forEach { key -> env[key]?.takeIf { it.isNotBlank() }?.let { vars[key] = it } }
    val args = listOf("mcp", "serve", app)
    // Node-based agents (Claude Code, Cursor) can't spawn a .bat directly on Windows; cmd /c runs it.
    return if (launcher.toString().endsWith(".bat", ignoreCase = true)) {
        ServerEntry(entryName(app), "cmd", listOf("/c", launcher.toString()) + args, vars)
    } else {
        ServerEntry(entryName(app), launcher.toString(), args, vars)
    }
}

fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/** The jar this code runs from, or null when running from classes (tests, an IDE). */
fun codeSourceJar(): Path? =
    Engine::class.java.protectionDomain
        ?.codeSource
        ?.location
        ?.let { runCatching { Path.of(it.toURI()) }.getOrNull() }
        ?.takeIf { it.toString().endsWith(".jar") }

/**
 * The wrapper that started this Gantry when an installer manages it (`GANTRY_LAUNCHER`, set by install.sh's and
 * Homebrew's wrappers): a stable path that survives upgrades and finds its own Java.
 */
fun managedLauncher(): String? = System.getenv("GANTRY_LAUNCHER")?.takeIf { it.isNotBlank() }

/**
 * The launcher agents should start: the managed wrapper as given (its path is the stable one, so links are kept),
 * else this install's start script: `installDist` puts the jar in `lib/` and the start scripts in `bin/`.
 */
fun locateLauncher(
    jar: Path? = codeSourceJar(),
    windows: Boolean = isWindows(),
    managed: String? = managedLauncher(),
): Path? {
    if (managed != null) return Path.of(managed).takeIf { Files.isRegularFile(it) }
    val bin =
        jar
            ?.toAbsolutePath()
            ?.parent
            ?.parent
            ?.resolve("bin") ?: return null
    val launcher = bin.resolve(if (windows) "gantry.bat" else "gantry")
    return if (Files.isRegularFile(launcher)) launcher.toRealPath() else null
}

/** The first executable named [name] on [path] (with `.exe` / `.cmd` / `.bat` on Windows), as an absolute path. */
fun findOnPath(
    name: String,
    path: String? = System.getenv("PATH"),
    windows: Boolean = isWindows(),
): Path? {
    val names = if (windows) listOf("$name.exe", "$name.cmd", "$name.bat", name) else listOf(name)
    return path
        .orEmpty()
        .split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .flatMap { dir -> names.map { Path.of(dir).resolve(it) } }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        ?.toAbsolutePath()
}

/** Where docker is, as an absolute path: [configured] when it already is one (`GANTRY_DOCKER`), else found on [path]. */
fun resolveDocker(
    configured: String,
    path: String? = System.getenv("PATH"),
    windows: Boolean = isWindows(),
): String? = if (Path.of(configured).isAbsolute) configured else findOnPath(configured, path, windows)?.toString()

/**
 * Why [entry] would no longer start, or null when everything it points at still exists. Upgrading Gantry, Java or
 * Docker often moves them (versioned install paths), and the agent would only see a server that fails to start.
 */
fun staleness(entry: ServerEntry): String? {
    val launcher = if (entry.command == "cmd") entry.args.getOrNull(1) ?: entry.command else entry.command
    if (!Files.exists(Path.of(launcher))) return "the Gantry launcher $launcher no longer exists"
    entry.env["JAVA_HOME"]?.let { home ->
        val bin = Path.of(home, "bin")
        if (!Files.exists(bin.resolve("java")) && !Files.exists(bin.resolve("java.exe"))) return "the Java at $home no longer exists"
    }
    entry.env["GANTRY_DOCKER"]?.takeIf { Path.of(it).isAbsolute }?.let { docker ->
        if (!Files.exists(Path.of(docker))) return "docker at $docker no longer exists"
    }
    return null
}
