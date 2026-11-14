package dev.gantry.engine

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.name

/** Service ids older versions saved, by their current id; apps, export files and `add` still accept them. */
val LEGACY_SERVICE_IDS = mapOf("realtime" to "websocket")

/** Env variables older versions provided, by their current name; env renames saved for them carry over. */
val LEGACY_ENV_KEYS = mapOf("REALTIME_URL" to "WEBSOCKET_URL", "REALTIME_WS_URL" to "WEBSOCKET_WS_URL")

/** [names] with keys for variables older versions provided moved to their current names. */
fun upgradeEnvNames(names: Map<String, String>): Map<String, String> = names.mapKeys { (key, _) -> LEGACY_ENV_KEYS[key] ?: key }

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

    /** Finds a service by its name (`postgres`, `valkey`, ...), or by an id older versions used. */
    fun resolve(name: String): ServiceDef =
        services.firstOrNull { it.id == (LEGACY_SERVICE_IDS[name] ?: name) }
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
