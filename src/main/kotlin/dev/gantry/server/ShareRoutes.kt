package dev.gantry.server

import dev.gantry.db.SourceCheck
import dev.gantry.db.Sync
import dev.gantry.db.SyncJob
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.SourceSettings
import dev.gantry.share.Exporter
import dev.gantry.share.ImportRequest
import dev.gantry.share.Importer
import dev.gantry.share.encode
import dev.gantry.share.parseExportFile
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
private data class ImportValidation(
    val errors: List<String>,
    val exists: Boolean,
)

@Serializable
private data class ImportApplied(
    val ok: Boolean,
    val app: String,
)

@Serializable
private data class SourceResponse(
    val source: SourceSettings?,
    val passwordSaved: Boolean,
    val job: SyncJob?,
)

@Serializable
private data class SavedPassword(
    val password: String?,
)

/** The source settings as the database page saves them, with the password when one was typed. */
@Serializable
private data class SaveSourceRequest(
    val host: String = "",
    val port: Int = 0,
    val database: String = "",
    val user: String = "",
    val password: String? = null,
)

@Serializable
private data class SyncRequest(
    val source: SourceSettings = SourceSettings(),
    val password: String? = null,
)

@Serializable
private data class MissingFields(
    val error: String,
    val missing: List<String>,
)

@Serializable
private data class FieldError(
    val error: String,
    val field: String,
)

private suspend fun <T> blocking(work: () -> T): T = withContext(Dispatchers.IO) { work() }

fun Route.shareRoutes(
    engine: Engine,
    sync: Sync,
) {
    val importer = Importer(engine)

    get("/apps/{app}/export") {
        val app = call.parameters.getOrFail("app")
        val text = blocking { Exporter(engine).export(app).encode() }
        // Quoted, so names with spaces or dots survive every browser.
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$app.gantry.json\"")
        call.respondText(text, ContentType.Application.Json)
    }

    route("/import") {
        post("/preview") {
            val body = call.receive<JsonObject>()
            val file = body["file"] ?: throw GantryException("missing 'file'")
            // The UI may send the file as parsed JSON or as the raw text of an upload.
            val text = (file as? JsonPrimitive)?.takeIf { it.isString }?.content ?: file.toString()
            val asName = (body["as"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.ifBlank { null }
            call.respond(blocking { importer.preview(parseExportFile(text), asName) })
        }
        post("/validate") {
            val request = call.receive<ImportRequest>()
            val exists = blocking { engine.listApps().any { it.name == request.app } }
            call.respond(ImportValidation(blocking { importer.validate(request) }, exists))
        }
        post("/apply") {
            val request = call.receive<ImportRequest>()
            val app = blocking { importer.apply(request) }
            call.respond(ImportApplied(true, app.name))
        }
    }

    route("/apps/{app}/db/{service}") {
        get("/source") { call.respond(blocking { sourceResponse(engine, sync, app(), service()) }) }
        put("/source") {
            val request = call.receive<SaveSourceRequest>()
            val app = app()
            val id = engine.catalog.resolve(service()).id
            blocking {
                // Source first: moving it to another database drops the old password, which must not take the new one.
                engine.setSource(app, id, SourceSettings(request.host, request.port, request.database, request.user))
                request.password?.takeIf { it.isNotEmpty() }?.let { engine.secrets.setPassword(app, id, it) }
            }
            call.respond(blocking { sourceResponse(engine, sync, app(), service()) })
        }
        // The dashboard is local-only, like the env block that already shows the database passwords.
        get("/source/password") {
            val password = blocking { engine.secrets.password(app(), engine.catalog.resolve(service()).id) }
            call.respond(SavedPassword(password))
        }
        get("/sync") {
            val job = blocking { sync.job(app(), service()) }
            if (job == null) call.respond(HttpStatusCode.NotFound, ErrorBody("no sync has run")) else call.respond(job)
        }
        post("/sync") {
            val request = call.receive<SyncRequest>()
            val app = app()
            val service = service()
            val password =
                blocking {
                    request.password?.takeIf { it.isNotEmpty() } ?: savedPassword(engine, app, service, request.source)
                }
            val missing = missingFields(request.source, password)
            if (missing.isNotEmpty()) {
                call.respond(HttpStatusCode.BadRequest, MissingFields("missing: ${missing.joinToString(", ")}", missing))
                return@post
            }
            // start() re-checks in the background, so an early check is what lets the dashboard answer 409/502 at once.
            val probe = blocking { sync.check(app, service, request.source, password!!) }
            when (probe.result) {
                SourceCheck.AUTH -> call.respond(HttpStatusCode.Conflict, FieldError(probe.message, "password"))
                SourceCheck.UNREACHABLE -> call.respond(HttpStatusCode.BadGateway, ErrorBody(probe.message))
                SourceCheck.OK -> call.respond(blocking { sync.start(app, service, request.source, password!!) })
            }
        }
    }
}

private fun missingFields(
    source: SourceSettings,
    password: String?,
): List<String> =
    buildList {
        if (source.host.isEmpty()) add("host")
        if (source.port <= 0) add("port")
        if (source.database.isEmpty()) add("database")
        if (source.user.isEmpty()) add("user")
        if (password.isNullOrEmpty()) add("password")
    }

/** The saved password, only while [source] is still the database it was saved for, so it never follows an edit elsewhere. */
private fun savedPassword(
    engine: Engine,
    app: String,
    service: String,
    source: SourceSettings,
): String? {
    val id = engine.catalog.resolve(service).id
    val saved = engine.loadApp(app).service(id)?.source ?: return null
    return if (saved.sameDatabase(source)) engine.secrets.password(app, id) else null
}

private fun sourceResponse(
    engine: Engine,
    sync: Sync,
    app: String,
    service: String,
): SourceResponse {
    val id = engine.catalog.resolve(service).id
    val state = engine.loadApp(app).service(id) ?: throw GantryException("app '$app' has no $id service")
    return SourceResponse(state.source, engine.secrets.password(app, id) != null, sync.job(app, id))
}

private fun RoutingContext.app(): String = call.parameters.getOrFail("app")

private fun RoutingContext.service(): String = call.parameters.getOrFail("service")
