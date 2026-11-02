package dev.gantry.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Raised for problems the user can fix (bad input, missing app, Docker failure); shown verbatim. */
class GantryException(
    message: String,
) : RuntimeException(message)

/** Where a service's dashboard lives: an external URL, another service's UI, or a built-in explorer. */
@Serializable
data class UiDef(
    val external: String? = null,
    val via: String? = null,
    val builtin: String? = null,
)

/**
 * Optional UI/validation hints for an option; options without one are free-form text. [type] is `text`,
 * `boolean` (validated true/false) or `path` (stored as an absolute path).
 * A [secret] option's value is masked in the UI and never shared.
 */
@Serializable
data class OptionMeta(
    val type: String = "text",
    val help: String = "",
    val secret: Boolean = false,
)

/** A service as declared in `catalog/<id>/service.json`. Map-valued fields keep declaration order. */
@Serializable
data class ServiceDef(
    val id: String,
    val description: String,
    val options: Map<String, String> = emptyMap(),
    @SerialName("option_meta") val optionMeta: Map<String, OptionMeta> = emptyMap(),
    val ports: Map<String, Int> = emptyMap(),
    val requires: List<String> = emptyList(),
    val volumes: List<String> = emptyList(),
    val dirs: List<String> = emptyList(),
    val files: Map<String, String> = emptyMap(),
    @SerialName("post_up") val postUp: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val ui: UiDef? = null,
)

@Serializable
data class Preset(
    val description: String = "",
    val services: Map<String, Map<String, String>> = emptyMap(),
)

/** A service added to an app: option overrides plus the host ports allocated to it. */
@Serializable
data class ServiceState(
    val id: String,
    val options: Map<String, String> = emptyMap(),
    val ports: Map<String, Int> = emptyMap(),
    val source: SourceSettings? = null,
)

private val hostLabel = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
private val ipv6 = Regex("^[0-9A-Fa-f:.]+$")

/**
 * Accepts only a hostname, an IPv4 address or an IPv6 address (bare or in brackets). A source host is pasted into
 * JDBC URLs and dump commands, and can come from a shared config file: anything else (`/`, `?`, `@`, `:port`, ...)
 * could add driver options such as MySQL's local-file loading, or point the connection somewhere unexpected.
 */
fun checkSourceHost(host: String) {
    val inner = host.removeSurrounding("[", "]")
    val ok =
        if (':' in inner) {
            (inner == host || host.startsWith("[")) && ipv6.matches(inner) && inner.count { it == ':' } >= 2
        } else {
            host.length <= 253 && host.split('.').all { hostLabel.matches(it) }
        }
    if (!ok) throw GantryException("source host '$host' isn't a hostname or IP address")
}

/** The remote database a service syncs from. The password is kept in the secret store, not here. */
@Serializable
data class SourceSettings(
    val host: String = "",
    val port: Int = 0,
    val database: String = "",
    val user: String = "",
) {
    fun complete(): Boolean = host.isNotEmpty() && port > 0 && database.isNotEmpty() && user.isNotEmpty()

    /** Same host, port and database: a password saved for one is still meant for the other, whatever the user. */
    fun sameDatabase(other: SourceSettings): Boolean = host == other.host && port == other.port && database == other.database
}

/**
 * One container group. [envNames] maps a service's default env key to the name the application reads;
 * an empty name omits the variable.
 */
@Serializable
data class App(
    val name: String,
    val services: List<ServiceState> = emptyList(),
    val envNames: Map<String, String> = emptyMap(),
) {
    fun service(id: String): ServiceState? = services.firstOrNull { it.id == id }
}

/** One generated variable: the service's default [key], the [name] the app uses, and its [value]. */
@Serializable
data class EnvEntry(
    val service: String,
    val key: String,
    val name: String,
    val value: String,
    val omitted: Boolean,
)

@Serializable
data class UiLink(
    val kind: String,
    val url: String? = null,
    val name: String? = null,
    val note: String? = null,
)

@Serializable
data class ServiceStatus(
    val id: String,
    val description: String,
    val options: Map<String, String>,
    val ports: Map<String, Int>,
    val state: String,
    val health: String,
    val ui: UiLink?,
)

@Serializable
data class AppStatus(
    val name: String,
    val services: List<ServiceStatus>,
)
