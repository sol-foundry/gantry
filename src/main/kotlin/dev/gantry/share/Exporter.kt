package dev.gantry.share

import dev.gantry.engine.Engine
import dev.gantry.gateway.GATEWAY
import dev.gantry.gateway.configText
import java.nio.file.Files
import java.nio.file.Path

private val sqlServices = setOf("postgres", "mysql")

/** Turns an app into an [ExportFile]: its shape and settings, never secrets or host ports. */
class Exporter(
    private val engine: Engine,
) {
    fun export(app: String): ExportFile {
        val loaded = engine.loadApp(app)
        val services =
            loaded.services.associate { state ->
                val meta = engine.catalog.resolve(state.id).optionMeta
                val resolved = engine.serviceOptions(loaded, state.id)
                val kept = state.options.filterKeys { it != "migrations" && meta[it]?.secret != true }
                // Pinned so an importer gets the same database and role names even if its app is called something else.
                val pinned = if (state.id in sqlServices) resolved.filterKeys { it == "name" || it == "user" } else emptyMap()
                val migrations =
                    resolved["migrations"]?.let {
                        exportPath(Path.of(it), engine.appDir(app).resolve("migrations").resolve(state.id))
                    }
                state.id to ExportedService(options = kept + pinned, migrations = migrations, source = state.source)
            }
        // Renames of variables no service provides any more (left by a removed service) would make the import fail.
        val provided = engine.envEntries(loaded).map { it.key }.toSet()
        val gateway =
            loaded.service(GATEWAY)?.let {
                ExportedGateway(
                    config = configText(engine.appDir(app)),
                    authorizers = loaded.authorizers.map { a -> ExportedAuthorizer(a.name, storedPath(a.dir), a.port) },
                )
            }
        return ExportFile(app = app, services = services, envNames = loaded.envNames.filterKeys { it in provided }, gateway = gateway)
    }

    /** Null for the default directory (the importer makes its own); repo-relative when inside a git repo. */
    internal fun exportPath(
        path: Path,
        defaultDir: Path,
    ): ExportedPath? {
        if (path.toAbsolutePath().normalize() == defaultDir.toAbsolutePath().normalize()) return null
        return repoPath(path)
    }
}

/** A pushed authorizer's stored dir (`<repo>/<path>`, or absolute) as an [ExportedPath]. */
internal fun storedPath(dir: String): ExportedPath =
    if (Path.of(dir).isAbsolute) ExportedPath(null, dir) else ExportedPath(dir.substringBefore('/'), dir.substringAfter('/', ""))

/** [storedPath] reversed: how an authorizer's dir is stored with the app. */
internal fun storedDir(path: ExportedPath): String = listOfNotNull(path.repo, path.path.ifEmpty { null }).joinToString("/")

/**
 * Where [path] is, independent of this machine: the name of the git repository holding it plus the path inside, or
 * the absolute path when no repository holds it.
 */
internal fun repoPath(path: Path): ExportedPath {
    val target = path.toAbsolutePath().normalize()
    var dir: Path? = target
    while (dir != null) {
        val name = dir.fileName
        if (name != null && Files.exists(dir.resolve(".git"))) {
            return ExportedPath(name.toString(), dir.relativize(target).joinToString("/"))
        }
        dir = dir.parent
    }
    return ExportedPath(null, target.toString())
}
