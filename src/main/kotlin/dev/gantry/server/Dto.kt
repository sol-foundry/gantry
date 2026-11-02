package dev.gantry.server

import dev.gantry.engine.OptionMeta
import kotlinx.serialization.Serializable

@Serializable
data class ErrorBody(
    val error: String,
)

@Serializable
data class Ok(
    val ok: Boolean = true,
)

@Serializable
data class CatalogService(
    val id: String,
    val description: String,
    val options: Map<String, String>,
    val optionOrder: List<String>,
    val optionMeta: Map<String, OptionMeta>,
    val requires: List<String>,
    /** Port name -> preferred host port (the actual one is allocated per app). */
    val ports: Map<String, Int> = emptyMap(),
    /** Default env keys the service contributes. */
    val env: List<String> = emptyList(),
    /** builtin explorer route, "external", or the service whose UI it uses. */
    val ui: String? = null,
)

@Serializable
data class CatalogResponse(
    val services: List<CatalogService>,
    val presets: List<String>,
)

@Serializable
data class AppSummary(
    val name: String,
    val services: List<String>,
    /** Services whose container is running right now. */
    val running: Int = 0,
    /** Service id -> its own web UI (RedisInsight for valkey), for services that link out rather than have an explorer. */
    val externalUis: Map<String, String> = emptyMap(),
)

@Serializable
data class CreateAppRequest(
    val name: String,
    val preset: String = "",
    val services: List<String> = emptyList(),
)

@Serializable
data class AddServiceRequest(
    val service: String,
    val options: Map<String, String> = emptyMap(),
    /** Delete the data volume a removed copy of this database left behind before adding it. */
    val freshData: Boolean = false,
)

/** The data volume a removed database left behind, if any. */
@Serializable
data class LeftoverVolume(
    val volume: String?,
)

@Serializable
data class EnvNamesRequest(
    val names: Map<String, String> = emptyMap(),
)

@Serializable
data class LogsResponse(
    val logs: String,
)

@Serializable
data class SqlRequest(
    val sql: String,
)

@Serializable
data class RedriveResponse(
    val moved: Int,
)

@Serializable
data class SendRequest(
    val body: String,
)

/** The outcome of adding, changing or removing a service: [applied] when the running app was updated at once. */
@Serializable
data class ServiceChange(
    val applied: Boolean,
    val notes: List<String> = emptyList(),
)

@Serializable
data class UpResult(
    val ok: Boolean = true,
    /** One line per database that ran migrations on `up` (migrate_on_up). */
    val notes: List<String> = emptyList(),
)

@Serializable
data class CountResult(
    val count: Long,
)

@Serializable
data class ScriptResponse(
    val name: String,
    val sql: String,
)

@Serializable
data class NewMigrationRequest(
    val description: String,
    val sql: String,
)
