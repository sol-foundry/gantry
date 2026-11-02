package dev.gantry.engine

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal val appNameRule = Regex("^[a-z][a-z0-9-]*$")

/** Services whose credentials are fixed into their data volume when it is first created. */
val DATABASE_SERVICES = setOf("postgres", "mysql")
private val envNameRule = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
private const val CONFIG_HASH_BYTES = 8
private const val LEGACY_CACHE_UI = "redisinsight"
private val portRegistrySerializer = MapSerializer(String.serializer(), Int.serializer())

/**
 * Manages apps under [home]: which services each has, their ports and options, and the generated
 * compose/config/env files. Container lifecycle lives in [Docker].
 *
 * Compose projects are named `gantry-<app>` on the shared Docker daemon, whatever [home] is, so tests
 * pass a [dockerCommand] that does not exist rather than risk touching a real app's containers.
 */
class Engine(
    val home: Path = defaultHome(),
    val catalog: Catalog = Catalog.load(),
    val dockerCommand: String = defaultDockerCommand(),
) {
    val docker = Docker(this)
    val secrets = SecretStore(home)

    fun appDir(name: String): Path = home.resolve("apps").resolve(name)

    private fun appFile(name: String) = appDir(name).resolve("app.json")

    private val portsFile get() = home.resolve("ports.json")

    fun loadApp(name: String): App = upgrade(decodeApp(name))

    /**
     * The app as saved, without side effects: legacy state is cleaned up in memory only, never written back. For
     * readers that run alongside `up` and the dashboard, such as the agent server.
     */
    fun readApp(name: String): App = decodeApp(name).let { app -> app.copy(services = app.services.filter { it.id != LEGACY_CACHE_UI }) }

    private fun decodeApp(name: String): App {
        if (!appNameRule.matches(name)) throw GantryException("invalid app name '$name'")
        val file = appFile(name)
        if (!Files.exists(file)) throw GantryException("app '$name' does not exist (run `gantry create $name`)")
        return json.decodeFromString(Files.readString(file))
    }

    /**
     * Rewrites state saved by older versions. RedisInsight used to be its own service; it is now valkey's UI, so the
     * entry is dropped and its host port moves to valkey's `ui` port. Container and volume names are unchanged, so
     * the running UI and its registered connection carry over on the next `up`.
     */
    @Synchronized
    private fun upgrade(app: App): App {
        if (app.service(LEGACY_CACHE_UI) == null) return app
        updatePorts { ports ->
            ports.remove("${app.name}/$LEGACY_CACHE_UI/ui")?.let { port -> ports.putIfAbsent("${app.name}/valkey/ui", port) }
        }
        return render(app.copy(services = app.services.filter { it.id != LEGACY_CACHE_UI }))
    }

    fun listApps(): List<App> {
        val apps = home.resolve("apps")
        if (!Files.isDirectory(apps)) return emptyList()
        return Files.list(apps).use { dirs ->
            dirs
                .filter {
                    Files.exists(it.resolve("app.json"))
                }.sorted()
                .map { upgrade(json.decodeFromString<App>(Files.readString(it.resolve("app.json")))) }
                .toList()
        }
    }

    @Synchronized
    fun create(
        name: String,
        preset: String? = null,
        services: List<String> = emptyList(),
    ): App {
        if (!appNameRule.matches(name)) throw GantryException("app name must match [a-z][a-z0-9-]*")
        if (Files.exists(appFile(name))) throw GantryException("app '$name' already exists")
        var app = App(name)
        if (!preset.isNullOrEmpty()) {
            Presets.load(preset).services.forEach { (service, options) -> app = withService(app, service, options) }
        }
        services.forEach { app = withService(app, it, emptyMap()) }
        return render(app)
    }

    /** Regenerates the app's compose, config and env files from its stored state. */
    @Synchronized
    fun refresh(appName: String): App = render(loadApp(appName))

    /**
     * Adds or reconfigures a service. With [freshData], the data volume a removed copy of a database left behind is
     * deleted first, so the new database starts empty with the credentials it is given now.
     */
    @Synchronized
    fun add(
        appName: String,
        service: String,
        options: Map<String, String> = emptyMap(),
        freshData: Boolean = false,
    ): App {
        val app = loadApp(appName)
        if (freshData) leftoverVolume(appName, service)?.let { docker.removeVolume(it, "$appName-${catalog.resolve(service).id}") }
        return render(withService(app, service, options))
    }

    /**
     * The data volume of a database that was removed from the app but kept its data. It still holds the credentials it
     * was created with, so a database added again with new ones cannot log in until the volume is deleted.
     */
    fun leftoverVolume(
        appName: String,
        service: String,
    ): String? {
        val id = catalog.resolve(service).id
        if (id !in DATABASE_SERVICES || loadApp(appName).service(id) != null) return null
        return "gantry-${appName}_$id-data".takeIf { docker.volumeExists(it) }
    }

    @Synchronized
    fun remove(
        appName: String,
        service: String,
    ): App {
        val app = loadApp(appName)
        val id = catalog.resolve(service).id
        if (app.service(id) == null) throw GantryException("'$id' is not in '$appName'")
        val dependents = app.services.filter { id in catalog.resolve(it.id).requires }.map { it.id }
        if (dependents.isNotEmpty()) throw GantryException("'$id' is required by ${dependents.joinToString()}; remove that first")
        updatePorts { ports -> ports.keys.removeIf { it.startsWith("$appName/$id/") } }
        val removed = render(app.copy(services = app.services.filter { it.id != id }))
        secrets.forget(appName, id)
        return removed
    }

    /**
     * Stores [app] as the whole state of that app, creating it if needed. Ports of services that remain are kept;
     * those of services no longer present go back to the pool.
     */
    @Synchronized
    fun replaceApp(app: App): App {
        if (!appNameRule.matches(app.name)) throw GantryException("app name must match [a-z][a-z0-9-]*")
        val services =
            app.services.map { state ->
                val def = catalog.resolve(state.id)
                ServiceState(def.id, normalizeOptions(def, state.options), source = state.source)
            }
        services.groupBy { it.id }.values.firstOrNull { it.size > 1 }?.let {
            throw GantryException("'${it[0].id}' is listed more than once")
        }
        services.forEach { state ->
            catalog.resolve(state.id).requires.firstOrNull { required -> services.none { it.id == required } }?.let {
                throw GantryException("'${state.id}' requires '$it'")
            }
        }
        val replaced = withEnvNames(App(app.name, services), app.envNames)
        val rendered = render(replaced)
        // Released only after rendering succeeded, so a rejected config leaves the old app's ports intact.
        val kept = services.map { it.id }.toSet()
        updatePorts { ports ->
            ports.keys.removeIf { key ->
                val parts = key.split('/')
                parts[0] == app.name && parts.getOrNull(1) !in kept
            }
        }
        return rendered
    }

    /**
     * Replaces the app's variable renames. A key mapped to "" is omitted; a key mapped to itself keeps
     * its default. The result must not emit the same name twice.
     */
    @Synchronized
    fun setEnvNames(
        appName: String,
        names: Map<String, String>,
    ): App = render(withEnvNames(loadApp(appName), names))

    /** [app] with [names] as its renames, after checking them against the variables its services provide. */
    internal fun withEnvNames(
        app: App,
        names: Map<String, String>,
    ): App {
        // Keys only, not rendered values: an app being created or replaced has no ports allocated yet.
        val keys = app.services.flatMap { catalog.resolve(it.id).env.keys }
        val known = keys.toSet()
        val cleaned =
            names.filter { (key, name) ->
                if (key !in known) throw GantryException("'$key' is not a variable provided by '${app.name}' services")
                if (name.isNotEmpty() && !envNameRule.matches(name)) {
                    throw GantryException("invalid variable name '$name' (use letters, digits and underscores)")
                }
                name != key
            }
        val emitted = keys.map { it to (cleaned[it] ?: it) }.filter { it.second.isNotEmpty() }
        emitted.groupBy { it.second }.values.firstOrNull { it.size > 1 }?.let { clash ->
            throw GantryException("'${clash[0].first}' and '${clash[1].first}' would both be emitted as ${clash[0].second}")
        }
        return app.copy(envNames = cleaned)
    }

    /**
     * Sets or clears (null) where the service syncs its data from. The saved password was typed for one database, so
     * it is dropped when the source moves to another host, port or database, or goes away.
     */
    @Synchronized
    fun setSource(
        app: String,
        service: String,
        source: SourceSettings?,
    ): App {
        source?.host?.takeIf { it.isNotBlank() }?.let(::checkSourceHost)
        val loaded = loadApp(app)
        val id = catalog.resolve(service).id
        val state = loaded.service(id) ?: throw GantryException("'$service' is not in '$app'")
        val updated = render(loaded.copy(services = loaded.services.map { if (it === state) it.copy(source = source) else it }))
        if (source == null || state.source?.sameDatabase(source) != true) secrets.forget(app, id)
        return updated
    }

    /** Every variable the app's services provide, with the app's renames applied. */
    fun envEntries(app: App): List<EnvEntry> =
        app.services.flatMap { state ->
            val context = context(app, state)
            catalog.resolve(state.id).env.map { (key, template) ->
                val custom = app.envNames[key]
                EnvEntry(
                    service = state.id,
                    key = key,
                    name = custom ?: key,
                    value = Template.render(template, context),
                    omitted = custom == "",
                )
            }
        }

    /** The variables to emit, under the names the application expects. */
    fun env(app: App): List<Pair<String, String>> = envEntries(app).filter { !it.omitted }.map { it.name to it.value }

    fun envText(app: App): String = env(app).joinToString("") { (name, value) -> "$name=${quoteEnvValue(value)}\n" }

    /** Effective options of one service: catalog defaults overlaid with the app's overrides. */
    fun serviceOptions(
        app: App,
        serviceId: String,
    ): Map<String, String> {
        val state = app.service(serviceId) ?: throw GantryException("'$serviceId' is not in '${app.name}'")
        val context = context(app, state)
        return catalog
            .resolve(serviceId)
            .options.keys
            .associateWith { context.getValue("opt.$it") }
    }

    private fun withService(
        app: App,
        service: String,
        options: Map<String, String>,
    ): App {
        val def = catalog.resolve(service)
        val normalized = normalizeOptions(def, options)
        val existing = app.service(def.id)
        // A new service gets its own random secrets (database passwords) unless they are given; existing ones keep theirs.
        val generated =
            if (existing != null) {
                emptyMap()
            } else {
                def.optionMeta
                    .filterValues { it.secret }
                    .keys
                    .filter { it in def.options && it !in normalized }
                    .associateWith { generateSecret() }
            }
        val updated = ServiceState(def.id, (existing?.options ?: emptyMap()) + generated + normalized, source = existing?.source)
        var result =
            app.copy(
                services =
                    if (existing ==
                        null
                    ) {
                        app.services + updated
                    } else {
                        app.services.map { if (it.id == def.id) updated else it }
                    },
            )
        def.requires.filter { result.service(it) == null }.forEach { result = withService(result, it, emptyMap()) }
        return result
    }

    /** Rejects unknown options and non-boolean values for boolean options; path options come back absolute. */
    internal fun normalizeOptions(
        def: ServiceDef,
        options: Map<String, String>,
    ): Map<String, String> {
        options.keys.firstOrNull { it !in def.options }?.let {
            throw GantryException("'${def.id}' has no option '$it' (valid: ${def.options.keys.joinToString().ifEmpty { "none" }})")
        }
        return options.mapValues { (key, value) ->
            when (def.optionMeta[key]?.type) {
                "boolean" -> {
                    if (value != "true" && value != "false") {
                        throw GantryException("option '$key' of '${def.id}' must be true or false, got '$value'")
                    }
                    value
                }

                "path" -> {
                    absolutePath(value)
                }

                else -> {
                    value
                }
            }
        }
    }

    /** Paths are stored absolute: `~` is expanded and relative paths resolve against the working directory. */
    private fun absolutePath(value: String): String {
        val expanded = if (value == "~" || value.startsWith("~/")) System.getProperty("user.home") + value.drop(1) else value
        return Path
            .of(expanded)
            .toAbsolutePath()
            .normalize()
            .toString()
    }

    private fun context(
        app: App,
        state: ServiceState,
    ): Context {
        val def = catalog.resolve(state.id)
        val dir = appDir(app.name).toAbsolutePath()
        val context =
            mutableMapOf(
                "app" to app.name,
                "app_id" to app.name.replace('-', '_'),
                "dir" to dir.toString(),
                "svc_dir" to dir.resolve("services").resolve(state.id).toString(),
            )
        def.options.forEach { (key, default) -> context["opt.$key"] = state.options[key] ?: default }
        // Option defaults may reference each other or the app (e.g. database name defaults to {{app_id}}).
        def.options.keys.forEach { context["opt.$it"] = Template.render(context.getValue("opt.$it"), context) }
        state.ports.forEach { (name, port) -> context["port.$name"] = port.toString() }
        return context
    }

    /** Allocates ports, then writes app.json, compose.yml, generated config files and the env file. */
    private fun render(initial: App): App {
        val app = allocatePorts(initial)
        val dir = appDir(app.name)
        Files.createDirectories(dir)

        val fragments = mutableListOf<String>()
        val volumes = linkedSetOf<String>()
        for (state in app.services) {
            val def = catalog.resolve(state.id)
            val context = context(app, state)
            val serviceDir = Path.of(context.getValue("svc_dir"))
            Resources.copyTree(Resources.directory("catalog").resolve(def.id), serviceDir)
            def.dirs.forEach { Files.createDirectories(dir.resolve(it)) }
            val rendered =
                def.files.map { (output, template) ->
                    renderTemplate(serviceDir.resolve(template), context).also { Files.writeString(dir.resolve(output), it) }
                }
            val fragment =
                withConfigHash(
                    renderTemplate(serviceDir.resolve("services.yml"), context, yamlEscape = true).trimEnd('\n') + "\n",
                    rendered,
                )
            fragments += fragment
            volumes += def.volumes.filter { isMounted(fragment, it) }
        }
        // Saved only once every template rendered, so options a template rejects are never stored.
        // Atomic: the agent server and the dashboard read app.json while `up` rewrites it.
        writeAtomically(appFile(app.name), json.encodeToString(App.serializer(), app) + "\n")
        Files.writeString(dir.resolve("compose.yml"), composeFile(app.name, fragments, volumes))
        Files.writeString(dir.resolve("${app.name}.env"), "# Generated by `gantry env ${app.name}`\n${envText(app)}")
        return app
    }

    /**
     * Labels the fragment's first service with a hash of its generated files. Compose only recreates a
     * container when its definition changes, so without this an edited bind-mounted config (e.g. a new queue)
     * would be ignored by `up` on a running app.
     */
    private fun withConfigHash(
        fragment: String,
        files: List<String>,
    ): String {
        if (files.isEmpty()) return fragment
        val digest = MessageDigest.getInstance("SHA-256").digest(files.joinToString("\u0000").toByteArray())
        val hash = digest.take(CONFIG_HASH_BYTES).joinToString("") { "%02x".format(it) }
        val firstLineEnd = fragment.indexOf('\n') + 1
        return fragment.substring(0, firstLineEnd) +
            "    labels:\n      gantry.config-hash: \"$hash\"\n" +
            fragment.substring(firstLineEnd)
    }

    private fun renderTemplate(
        file: Path,
        context: Context,
        yamlEscape: Boolean = false,
    ): String = Template.render(Template.expandComputed(Files.readString(file), context), context, yamlEscape)

    /** A declared volume is only emitted when the rendered service actually mounts it (options can switch it off). */
    private fun isMounted(
        fragment: String,
        volume: String,
    ) = Regex("""(?m)^\s+-\s+"?${Regex.escape(volume)}:""").containsMatchIn(fragment)

    private fun composeFile(
        name: String,
        fragments: List<String>,
        volumes: Set<String>,
    ): String {
        if (fragments.isEmpty()) return "name: gantry-$name\nservices: {}\n"
        val volumeSection = if (volumes.isEmpty()) "" else "\nvolumes:\n" + volumes.joinToString("") { "  $it:\n" }
        return "# Generated by `gantry` for app '$name'. Change with `gantry add/remove`.\n" +
            "name: gantry-$name\n\nservices:\n${fragments.joinToString("\n")}$volumeSection"
    }

    private fun allocatePorts(app: App): App {
        var allocated = app
        updatePorts { registry ->
            allocated =
                app.copy(
                    services =
                        app.services.map { state ->
                            val ports =
                                catalog.resolve(state.id).ports.toSortedMap().mapValues { (portName, default) ->
                                    registry.getOrPut(
                                        "${app.name}/${state.id}/$portName",
                                    ) { nextFreePort(default, registry.values.toSet()) }
                                }
                            state.copy(ports = ports)
                        },
                )
        }
        return allocated
    }

    private fun nextFreePort(
        preferred: Int,
        taken: Set<Int>,
    ): Int = generateSequence(preferred) { it + 1 }.first { it !in taken && hostPortFree(it) }

    private fun hostPortFree(port: Int): Boolean =
        try {
            ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { true }
        } catch (_: java.io.IOException) {
            false
        }

    internal fun updatePorts(change: (MutableMap<String, Int>) -> Unit) {
        val registry =
            if (Files.exists(portsFile)) {
                json.decodeFromString(portRegistrySerializer, Files.readString(portsFile)).toMutableMap()
            } else {
                mutableMapOf()
            }
        change(registry)
        Files.createDirectories(home)
        writeAtomically(portsFile, json.encodeToString(portRegistrySerializer, registry.toSortedMap()) + "\n")
    }

    companion object {
        /** `GANTRY_DOCKER` points at a docker binary when it isn't on `PATH` (e.g. for agents started from a GUI). */
        fun defaultDockerCommand(env: Map<String, String> = System.getenv()): String = env["GANTRY_DOCKER"]?.ifBlank { null } ?: "docker"

        fun defaultHome(): Path =
            System.getenv("GANTRY_HOME")?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".gantry")
    }
}

/** Replaces [target] with [text] so readers see the old file or the new one, never a half-written one. */
fun writeAtomically(
    target: Path,
    text: String,
) {
    val temp = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
    try {
        Files.writeString(temp, text)
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}
