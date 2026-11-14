package dev.gantry.server

import dev.gantry.db.Sync
import dev.gantry.db.SyncContainers
import dev.gantry.engine.Engine
import dev.gantry.engine.NO_DOCKER
import dev.gantry.engine.SourceSettings
import dev.gantry.share.ExportFile
import dev.gantry.share.ExportedAuthorizer
import dev.gantry.share.ExportedGateway
import dev.gantry.share.ExportedPath
import dev.gantry.share.ExportedService
import dev.gantry.share.Importer
import dev.gantry.share.encode
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareRoutesTest {
    private val gantry =
        Engine(
            home = Files.createTempDirectory("gantry-share-routes-test"),
            dockerCommand = NO_DOCKER,
            gatewayLib = Files.createTempDirectory("gantry-lib").also { Files.writeString(it.resolve("gantry.jar"), "jar") },
        )

    private val noContainers =
        object : SyncContainers {
            override fun upService(
                app: String,
                service: String,
            ) = error("must not run")

            override fun run(
                args: List<String>,
                env: Map<String, String>,
                onLine: (String) -> Unit,
            ): Int = error("must not run")

            override fun remove(name: String) = error("must not run")
        }

    private fun dashboard(
        sync: Sync = Sync(gantry),
        importer: Importer = Importer(gantry) { false },
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { dashboardModule(gantry, sync, importer = importer) }
        block()
    }

    private suspend fun ApplicationTestBuilder.postJson(
        path: String,
        body: String,
    ) = client.post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `export download names the file`() =
        dashboard {
            gantry.create("shop", services = listOf("postgres"))

            val response = client.get("/api/apps/shop/export")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""attachment; filename="shop.gantry.json"""", response.headers[HttpHeaders.ContentDisposition])
            assertContains(response.bodyAsText(), "\"app\"")
        }

    @Test
    fun `preview rejects a bad file with 400`() =
        dashboard {
            val response = postJson("/api/import/preview", """{"file":"not json at all"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertContains(response.bodyAsText(), "not a Gantry export")
        }

    @Test
    fun `apply creates the app`() =
        dashboard {
            gantry.create("shop", services = listOf("postgres"))
            val exported = client.get("/api/apps/shop/export").bodyAsText()
            val preview = postJson("/api/import/preview", """{"file":$exported,"as":"copy"}""")
            assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())
            val request = Regex("\"request\":(.*),\"exists\"", RegexOption.DOT_MATCHES_ALL).find(preview.bodyAsText())!!.groupValues[1]

            val validated = postJson("/api/import/validate", request)
            assertContains(validated.bodyAsText(), "\"exists\":false")
            val applied = postJson("/api/import/apply", request)

            assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
            assertContains(applied.bodyAsText(), "\"app\":\"copy\"")
            assertTrue(gantry.listApps().any { it.name == "copy" })
        }

    @Test
    fun `sync with missing fields lists them`() =
        dashboard {
            gantry.create("shop", services = listOf("postgres"))

            val response = postJson("/api/apps/shop/db/postgres/sync", """{"source":{"port":5432,"database":"shop","user":"app"}}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertContains(body, "\"missing\"")
            assertContains(body, "\"host\"")
            assertContains(body, "\"password\"")
        }

    @Test
    fun `sync to another host does not use the saved password`() =
        dashboard {
            gantry.create("shop", services = listOf("postgres"))
            gantry.setSource("shop", "postgres", SourceSettings("stage.example", 5432, "shop", "app"))
            gantry.secrets.setPassword("shop", "postgres", "stage-pw")

            val response =
                postJson(
                    "/api/apps/shop/db/postgres/sync",
                    """{"source":{"host":"evil.example","port":5432,"database":"shop","user":"app"}}""",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertContains(response.bodyAsText(), "\"missing\":[\"password\"]")
        }

    @Test
    fun `sync auth failure is 409 with field password`() {
        gantry.create("shop", services = listOf("postgres"))
        val sync =
            Sync(gantry, connect = {
                _,
                _,
                _,
                ->
                throw SQLException("password authentication failed", "28P01")
            }, containers = noContainers)
        dashboard(sync) {
            val response =
                postJson(
                    "/api/apps/shop/db/postgres/sync",
                    """{"source":{"host":"stage.example","port":5432,"database":"shop","user":"app"},"password":"nope"}""",
                )

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertContains(response.bodyAsText(), "\"field\":\"password\"")
        }
    }

    @Test
    fun `a table sync names its tables`() {
        gantry.create("shop", services = listOf("postgres"))
        // Answers only what the source check asks: the server's version.
        val meta =
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(java.sql.DatabaseMetaData::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getDatabaseMajorVersion" -> 16
                    "getDatabaseMinorVersion" -> 4
                    else -> "16.4"
                }
            }
        val connection =
            java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, _ ->
                if (method.name == "getMetaData") meta else null
            } as java.sql.Connection
        dashboard(Sync(gantry, connect = { _, _, _ -> connection }, containers = noContainers)) {
            val response =
                postJson(
                    "/api/apps/shop/db/postgres/sync",
                    """{"source":{"host":"stage.example","port":5432,"database":"shop","user":"app"},"password":"pw","tables":["users","config.flags"]}""",
                )

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertContains(response.bodyAsText(), "\"scope\":[\"public.users\",\"config.flags\"]")
        }
    }

    @Test
    fun `an unsafe table name is refused before the source is contacted`() {
        gantry.create("shop", services = listOf("postgres"))
        var contacted = false
        val sync = Sync(gantry, connect = { _, _, _ -> throw SQLException("unused").also { contacted = true } }, containers = noContainers)
        dashboard(sync) {
            val response =
                postJson(
                    "/api/apps/shop/db/postgres/sync",
                    """{"source":{"host":"stage.example","port":5432,"database":"shop","user":"app"},"password":"pw","tables":["x;drop"]}""",
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertContains(response.bodyAsText(), "\"field\":\"tables\"")
            assertEquals(false, contacted)
        }
    }

    @Test
    fun `sync unreachable is 502`() {
        gantry.create("shop", services = listOf("postgres"))
        val sync = Sync(gantry, connect = { _, _, _ -> throw SQLException("Connection refused", "08001") }, containers = noContainers)
        dashboard(sync) {
            val response =
                postJson(
                    "/api/apps/shop/db/postgres/sync",
                    """{"source":{"host":"stage.example","port":5432,"database":"shop","user":"app"},"password":"pw"}""",
                )

            assertEquals(HttpStatusCode.BadGateway, response.status)
        }
    }

    @Test
    fun `source is saved and read back`() =
        dashboard {
            gantry.create("shop", services = listOf("postgres"))
            val source = SourceSettings("stage.example", 5432, "shop", "app")
            val put =
                client.put("/api/apps/shop/db/postgres/source") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"host":"${source.host}","port":5432,"database":"shop","user":"app"}""")
                }
            assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())

            val body = client.get("/api/apps/shop/db/postgres/source").bodyAsText()
            assertContains(body, "stage.example")
            assertContains(body, "\"passwordSaved\":false")
            assertEquals(HttpStatusCode.NotFound, client.get("/api/apps/shop/db/postgres/sync").status)
        }

    @Test
    fun `saving source settings with a password keeps the password locally`() =
        dashboard {
            gantry.create("shop", services = listOf("postgres"))
            val put =
                client.put("/api/apps/shop/db/postgres/source") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"host":"stage.example","port":5432,"database":"shop","user":"app","password":"s3cret"}""")
                }
            assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
            assertContains(put.bodyAsText(), "\"passwordSaved\":true")
            assertEquals("s3cret", gantry.secrets.password("shop", "postgres"))
            assertContains(client.get("/api/apps/shop/db/postgres/source/password").bodyAsText(), "\"password\":\"s3cret\"")
            assertTrue(!Files.readString(gantry.appDir("shop").resolve("app.json")).contains("s3cret"))
        }

    @Test
    fun `a dashboard import applies the gateway section and reports what is left to do`() =
        dashboard {
            val yml = "authorizers:\n  mine:\n    type: custom\n    container: check\n    env:\n      KEY: \"{{secret:API_KEY}}\"\n"
            val file =
                ExportFile(
                    app = "shop",
                    services = mapOf("gateway" to ExportedService()),
                    gateway = ExportedGateway(yml, listOf(ExportedAuthorizer("check", ExportedPath(null, "/src/check")))),
                )
            val preview = postJson("/api/import/preview", Json.encodeToString(mapOf("file" to file.encode())))
            assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())
            // The dialog sends back the preview's request as it is, edited in place.
            val request =
                Json
                    .parseToJsonElement(preview.bodyAsText())
                    .jsonObject
                    .getValue("request")
                    .toString()
            assertContains(request, "\"gateway\"")

            val applied = postJson("/api/import/apply", request)

            assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
            assertEquals(yml, Files.readString(gantry.appDir("shop").resolve("gateway.yml")))
            val body = Json.parseToJsonElement(applied.bodyAsText()).jsonObject
            assertEquals(listOf("API_KEY"), body.getValue("unsetSecrets").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                "/src/check",
                body
                    .getValue("toPush")
                    .jsonObject
                    .getValue("check")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `validate reports a gateway section that would not render`() =
        dashboard {
            val file =
                ExportFile(
                    app = "shop",
                    services = mapOf("gateway" to ExportedService()),
                    gateway = ExportedGateway("upstream: http://localhost:9000/with/path\n"),
                )
            val preview = postJson("/api/import/preview", Json.encodeToString(mapOf("file" to file.encode())))
            val request =
                Json
                    .parseToJsonElement(preview.bodyAsText())
                    .jsonObject
                    .getValue("request")
                    .toString()

            val validation = postJson("/api/import/validate", request).bodyAsText()

            assertContains(validation, "must not have a path")
            assertEquals(HttpStatusCode.BadRequest, postJson("/api/import/apply", request).status)
            assertTrue(gantry.listApps().none { it.name == "shop" })
        }
}
