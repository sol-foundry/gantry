package dev.gantry.engine

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.name

internal val json =
    Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

/** The services an app can be built from, loaded from the bundled `catalog/` directory. */
class Catalog(
    private val services: List<ServiceDef>,
) {
    fun all(): List<ServiceDef> = services

    /** Finds a service by its name (`postgres`, `valkey`, ...). */
    fun resolve(name: String): ServiceDef =
        services.firstOrNull { it.id == name }
            ?: throw GantryException("unknown service '$name'; choose from ${services.joinToString { it.id }}")

    companion object {
        fun load(): Catalog {
            val root = Resources.directory("catalog")
            val defs =
                Files.list(root).use { dirs ->
                    dirs
                        .filter { Files.isDirectory(it) && Files.exists(it.resolve("service.json")) }
                        .sorted(compareBy { it.name })
                        .map { json.decodeFromString<ServiceDef>(Files.readString(it.resolve("service.json"))) }
                        .toList()
                }
            return Catalog(defs)
        }
    }
}

/** Named bundles of services, loaded from the bundled `presets/` directory. */
object Presets {
    fun names(): List<String> =
        Files.list(Resources.directory("presets")).use { files ->
            files
                .map { it.name }
                .filter { it.endsWith(".json") }
                .map { it.removeSuffix(".json") }
                .sorted()
                .toList()
        }

    fun load(name: String): Preset {
        val file = Resources.directory("presets").resolve("$name.json")
        if (!Files.exists(file)) throw GantryException("unknown preset '$name' (available: ${names().joinToString()})")
        return json.decodeFromString(Files.readString(file))
    }
}
