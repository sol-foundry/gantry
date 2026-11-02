package dev.gantry.share

import dev.gantry.engine.Engine
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
        return ExportFile(app = app, services = services, envNames = loaded.envNames)
    }

    /** Null for the default directory (the importer makes its own); repo-relative when inside a git repo. */
    internal fun exportPath(
        path: Path,
        defaultDir: Path,
    ): ExportedPath? {
        val target = path.toAbsolutePath().normalize()
        if (target == defaultDir.toAbsolutePath().normalize()) return null
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
}
