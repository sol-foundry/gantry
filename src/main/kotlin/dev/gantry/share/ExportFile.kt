package dev.gantry.share

import dev.gantry.engine.GantryException
import dev.gantry.engine.SourceSettings
import dev.gantry.engine.json
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val EXPORT_FORMAT = 1

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExportFile(
    // Always written: the engine's json drops defaults, and a reader needs the version to recognise the file.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val gantry: Int = EXPORT_FORMAT,
    val app: String,
    val services: Map<String, ExportedService>,
    val envNames: Map<String, String> = emptyMap(),
)

@Serializable
data class ExportedService(
    val options: Map<String, String> = emptyMap(),
    val migrations: ExportedPath? = null,
    val source: SourceSettings? = null,
)

@Serializable
data class ExportedPath(
    val repo: String? = null,
    val path: String,
)

fun ExportFile.encode(): String = json.encodeToString(ExportFile.serializer(), this)

fun parseExportFile(text: String): ExportFile {
    val file =
        try {
            val tree = json.parseToJsonElement(text) as? JsonObject ?: throw GantryException("not a Gantry export: expected a JSON object")
            if ("gantry" !in tree) throw GantryException("not a Gantry export: missing format version")
            json.decodeFromJsonElement(ExportFile.serializer(), tree)
        } catch (e: IllegalArgumentException) {
            // Includes SerializationException, for malformed JSON and fields of the wrong shape.
            throw GantryException("not a Gantry export: ${e.message}")
        }
    if (file.gantry != EXPORT_FORMAT) {
        throw GantryException("unsupported export format ${file.gantry} (this Gantry reads $EXPORT_FORMAT)")
    }
    return file
}
