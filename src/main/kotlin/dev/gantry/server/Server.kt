package dev.gantry.server

import dev.gantry.db.DatabaseExplorer
import dev.gantry.db.DdlRequest
import dev.gantry.db.Migrations
import dev.gantry.db.RowEdit
import dev.gantry.db.RowsDelete
import dev.gantry.db.Sync
import dev.gantry.engine.App
import dev.gantry.engine.Engine
import dev.gantry.engine.EnvEntry
import dev.gantry.engine.GantryException
import dev.gantry.engine.Presets
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.host
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Defaults are encoded so responses like `{"ok":true}` carry every field the UI reads. */
private val apiJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

private val localHosts = setOf("localhost", "127.0.0.1", "[::1]", "::1")
private const val DEFAULT_LOG_TAIL = 200
private const val MAX_LOG_TAIL = 2000
private const val PAGE_LIMIT_DEFAULT = 100

/** Starts the dashboard on 127.0.0.1:[port] and blocks. */
fun startDashboard(
    engine: Engine,
    port: Int,
) {
    println("dashboard: http://localhost:$port  (Ctrl-C to stop)")
    embeddedServer(CIO, host = "127.0.0.1", port = port) { dashboardModule(engine, Sync(engine)) }.start(wait = true)
}

fun Application.dashboardModule(
    engine: Engine,
    sync: Sync = Sync(engine),
) {
    install(ContentNegotiation) { json(apiJson) }
    install(StatusPages) {
        exception<GantryException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorBody(e.message ?: "error")) }
        exception<SerializationException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody("bad request: ${e.message}"))
        }
    }
    guardBrowserRequests()
    routing {
        route("/api") {
            val migrations = Migrations(engine)
            apiRoutes(engine, migrations, QueueExplorer(engine))
            databaseRoutes(DatabaseExplorer(engine, migrations), migrations)
            shareRoutes(engine, sync)
        }
        // Unknown API paths are a client bug, not a page: answer 404 rather than the SPA's index.html.
        route("/api/{path...}") { handle { call.respond(HttpStatusCode.NotFound, ErrorBody("no such endpoint")) } }
        get("{path...}") { serveWebApp(call.parameters.getAll("path").orEmpty()) }
    }
}

/**
 * Blocks DNS rebinding (non-local Host) and cross-site writes (foreign Origin). The API can run SQL
 * and manage containers, so no other web page may drive it.
 */
private fun Application.guardBrowserRequests() {
    intercept(ApplicationCallPipeline.Plugins) {
        val hostHeader = call.request.headers["Host"].orEmpty()
        val originAuthority = call.request.headers["Origin"]?.substringAfter("://")
        when {
            call.request.host() !in localHosts -> {
                call.respond(HttpStatusCode.Forbidden, ErrorBody("forbidden host"))
                finish()
            }
            originAuthority != null && originAuthority != hostHeader -> {
                call.respond(HttpStatusCode.Forbidden, ErrorBody("cross-origin request blocked"))
                finish()
            }
        }
    }
}

private suspend fun <T> blocking(work: () -> T): T = withContext(Dispatchers.IO) { work() }

private fun Route.apiRoutes(
    engine: Engine,
    migrations: Migrations,
    queues: QueueExplorer,
) {
    get("/catalog") {
        val services =
            engine.catalog.all().map {
                CatalogService(
                    id = it.id,
                    description = it.description,
                    options = it.options,
                    optionOrder = it.options.keys.toList(),
                    optionMeta = it.optionMeta,
                    requires = it.requires,
                    ports = it.ports,
                    env = it.env.keys.toList(),
                    ui = it.ui?.builtin ?: it.ui?.via ?: it.ui?.external?.let { "external" },
                )
            }
        call.respond(CatalogResponse(services, Presets.names()))
    }
    get("/apps") {
        val apps =
            blocking {
                val running = engine.docker.runningServices()
                engine.listApps().map { app ->
                    val ids = app.services.map { it.id }
                    val externalUis =
                        app.services
                            .mapNotNull { service ->
                                engine.docker
                                    .uiLink(app, service)
                                    ?.takeIf { it.kind == "external" }
                                    ?.url
                                    ?.let { service.id to it }
                            }.toMap()
                    AppSummary(app.name, ids, ids.count { it in running[app.name].orEmpty() }, externalUis)
                }
            }
        call.respond(apps)
    }
    post("/apps") {
        val request = call.receive<CreateAppRequest>()
        blocking { engine.create(request.name, request.preset, request.services) }
        call.respond(Ok())
    }

    route("/apps/{app}") {
        get { call.respond(blocking { engine.docker.status(app()) }) }
        post("/services") {
            val request = call.receive<AddServiceRequest>()
            val app = app()
            blocking { engine.add(app, request.service, request.options.filterValues { it.isNotEmpty() }, request.freshData) }
            call.respond(blocking { applyIfRunning(engine, migrations, app) })
        }
        get("/services/{service}/leftover") {
            call.respond(LeftoverVolume(blocking { engine.leftoverVolume(app(), call.parameters.getOrFail("service")) }))
        }
        delete("/services/{service}") {
            val app = app()
            blocking { engine.remove(app, call.parameters.getOrFail("service")) }
            call.respond(blocking { applyIfRunning(engine, migrations, app) })
        }
        post("/up") {
            val notes =
                blocking {
                    engine.docker.up(app())
                    migrations.migrateOnUp(app())
                }
            call.respond(UpResult(notes = notes))
        }
        post("/down") { blocking { engine.docker.down(app()) }.also { call.respond(Ok()) } }
        post("/destroy") { blocking { engine.docker.destroy(app()) }.also { call.respond(Ok()) } }
        get("/env") { call.respond(blocking { envResponse(engine, engine.refresh(app())) }) }
        put("/env-names") {
            val request = call.receive<EnvNamesRequest>()
            call.respond(blocking { envResponse(engine, engine.setEnvNames(app(), request.names)) })
        }
        get("/logs/{service}") {
            val tail =
                call.request.queryParameters["tail"]
                    ?.toIntOrNull()
                    ?.takeIf { it in 1..MAX_LOG_TAIL } ?: DEFAULT_LOG_TAIL
            call.respond(LogsResponse(blocking { engine.docker.logs(app(), call.parameters.getOrFail("service"), tail) }))
        }

        get("/queue/queues") { call.respond(blocking { queues.queues(app()) }) }
        get("/queue/{queue}/messages") { call.respond(blocking { queues.peek(app(), call.parameters.getOrFail("queue")) }) }
        post("/queue/{queue}/send") {
            val request = call.receive<SendRequest>()
            blocking { queues.send(app(), call.parameters.getOrFail("queue"), request.body) }
            call.respond(Ok())
        }
        post("/queue/{queue}/redrive") {
            call.respond(RedriveResponse(blocking { queues.redrive(app(), call.parameters.getOrFail("queue")) }))
        }
        post("/queue/{queue}/purge") {
            blocking { queues.purge(app(), call.parameters.getOrFail("queue")) }
            call.respond(Ok())
        }
    }
}

private fun Route.databaseRoutes(
    explorer: DatabaseExplorer,
    migrations: Migrations,
) {
    route("/apps/{app}/db/{service}") {
        get("/info") { call.respond(blocking { explorer.info(app(), service()) }) }
        get("/table") {
            val q = call.request.queryParameters
            call.respond(blocking { explorer.table(app(), service(), q["schema"].orEmpty(), q.getOrFail("name")) })
        }
        get("/rows") {
            val q = call.request.queryParameters
            val page =
                blocking {
                    explorer.rows(
                        app(),
                        service(),
                        q["schema"].orEmpty(),
                        q.getOrFail("name"),
                        q["limit"]?.toIntOrNull() ?: PAGE_LIMIT_DEFAULT,
                        q["offset"]?.toIntOrNull() ?: 0,
                        q["order"],
                        q["desc"] == "true",
                        q["where"],
                    )
                }
            call.respond(page)
        }
        post("/rows/insert") {
            val edit = call.receive<RowEdit>()
            blocking { explorer.insert(app(), service(), edit) }
            call.respond(Ok())
        }
        post("/rows/update") {
            val edit = call.receive<RowEdit>()
            blocking { explorer.update(app(), service(), edit) }
            call.respond(Ok())
        }
        post("/rows/delete") {
            val request = call.receive<RowsDelete>()
            call.respond(CountResult(blocking { explorer.delete(app(), service(), request) }.toLong()))
        }
        post("/sql") {
            val request = call.receive<SqlRequest>()
            call.respond(blocking { explorer.execute(app(), service(), request.sql) })
        }
        post("/ddl") {
            val request = call.receive<DdlRequest>()
            call.respond(blocking { explorer.ddl(app(), service(), request) })
        }
        get("/migrations") { call.respond(blocking { migrations.status(app(), service()) }) }
        get("/migrations/script") {
            val name = call.request.queryParameters.getOrFail("name")
            call.respond(ScriptResponse(name, blocking { migrations.script(app(), service(), name) }))
        }
        post("/migrations/new") {
            val request = call.receive<NewMigrationRequest>()
            call.respond(ScriptResponse(blocking { migrations.create(app(), service(), request.description, request.sql) }, request.sql))
        }
        post("/migrations/{action}") {
            val action = call.parameters.getOrFail("action")
            call.respond(blocking { migrations.run(app(), service(), action) })
        }
    }
}

/**
 * A service added, changed or removed while the app is running takes effect at once: the same `up` as Start, which
 * only recreates containers whose config changed and stops removed ones. A stopped app waits for Start.
 */
private fun applyIfRunning(
    engine: Engine,
    migrations: Migrations,
    app: String,
): ServiceChange {
    val running =
        runCatching {
            engine.docker
                .status(app)
                .services
                .any { it.state == "running" }
        }.getOrDefault(false)
    if (!running) return ServiceChange(applied = false)
    // With its last service removed there is nothing to bring up, only the old container to stop.
    if (engine.loadApp(app).services.isEmpty()) {
        engine.docker.down(app)
        return ServiceChange(applied = true)
    }
    engine.docker.up(app)
    return ServiceChange(applied = true, notes = migrations.migrateOnUp(app))
}

private fun RoutingContext.app(): String = call.parameters.getOrFail("app")

private fun RoutingContext.service(): String = call.parameters.getOrFail("service")

@Serializable
data class EnvResponse(
    val entries: List<EnvEntry>,
    val text: String,
)

private fun envResponse(
    engine: Engine,
    app: App,
) = EnvResponse(engine.envEntries(app), engine.envText(app))

/** Serves the bundled React build, falling back to index.html so client-side routes survive a reload. */
private suspend fun RoutingContext.serveWebApp(segments: List<String>) {
    val path = segments.joinToString("/")
    val asset = if (path.isNotEmpty() && ".." !in segments) readResource("web/$path") else null
    if (asset != null) {
        call.respondBytes(asset, ContentType.defaultForFilePath(path))
        return
    }
    val index = readResource("web/index.html")
    if (index == null) {
        call.respondText(
            "Dashboard UI is not bundled in this build. Run `./gradlew build` without -PskipWeb.",
            status = HttpStatusCode.NotFound,
        )
    } else {
        call.respondBytes(index, ContentType.Text.Html)
    }
}

private fun readResource(name: String): ByteArray? =
    Engine::class.java.classLoader
        .getResourceAsStream(name)
        ?.use { it.readBytes() }
