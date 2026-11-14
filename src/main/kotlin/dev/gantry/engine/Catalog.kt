package dev.gantry.engine

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.name

/**
 * Services older versions had that now live inside another, by their current id: apps, export files and `add` still
 * accept them. `realtime`, later `websocket`, is the gateway's WebSocket half.
 */
val LEGACY_SERVICE_IDS = mapOf("realtime" to "gateway", "websocket" to "gateway")

/** For a folded service, where each of its host ports lives now, by port name. */
val LEGACY_PORT_NAMES = mapOf("realtime" to mapOf("main" to "websocket"), "websocket" to mapOf("main" to "websocket"))

/** Env variables older versions provided, by their current name; env renames saved for them carry over. */
val LEGACY_ENV_KEYS =
    mapOf(
        "REALTIME_URL" to "GATEWAY_WS_MANAGEMENT_URL",
        "REALTIME_WS_URL" to "GATEWAY_WS_URL",
        "WEBSOCKET_URL" to "GATEWAY_WS_MANAGEMENT_URL",
        "WEBSOCKET_WS_URL" to "GATEWAY_WS_URL",
    )

/** [names] with keys for variables older versions provided moved to their current names. */
fun upgradeEnvNames(names: Map<String, String>): Map<String, String> = names.mapKeys { (key, _) -> LEGACY_ENV_KEYS[key] ?: key }

/**
 * [services] with every folded service replaced by the one that holds it now. Its own options are dropped (the same
 * name can mean something else there, such as `version`), and an app that already has that service keeps its own.
 */
fun <T> foldLegacyServices(
    services: List<T>,
    id: (T) -> String,
    fresh: (T, String) -> T,
): List<T> {
    val current = services.filter { id(it) !in LEGACY_SERVICE_IDS }
    val folded =
        services
            .filter { id(it) in LEGACY_SERVICE_IDS }
            .map { fresh(it, LEGACY_SERVICE_IDS.getValue(id(it))) }
            .filter { f -> current.none { id(it) == id(f) } }
            .distinctBy(id)
    return current + folded
}

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
