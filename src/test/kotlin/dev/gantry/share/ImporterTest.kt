package dev.gantry.share

import dev.gantry.engine.App
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.NO_DOCKER
import dev.gantry.engine.ServiceState
import dev.gantry.engine.SourceSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImporterTest {
    private val home: Path = Files.createTempDirectory("gantry-import")
    private val engine = Engine(home = home, dockerCommand = NO_DOCKER)
    private val nobody: Path = Files.createTempDirectory("home")

    private fun file(
        app: String = "shop",
        services: Map<String, ExportedService>,
        envNames: Map<String, String> = emptyMap(),
    ) = ExportFile(app = app, services = services, envNames = envNames)

    private fun preview(
        file: ExportFile,
        asName: String? = null,
        home: Path = nobody,
    ) = Importer(engine).preview(file, asName, home = home, cwd = nobody)

    private fun ports() = Files.readString(home.resolve("ports.json"))

    private fun storedOptions(
        app: String,
        service: String,
    ): Map<String, String> {
        val stored = Json.parseToJsonElement(Files.readString(engine.appDir(app).resolve("app.json"))).jsonObject
        val state =
            stored
                .getValue("services")
                .jsonArray
                .map { it.jsonObject }
                .first { it.getValue("id").jsonPrimitive.content == service }
        return state["options"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
    }

    @Test
    fun `preview of a new app fills defaults and generates secrets`() {
        val p = preview(file(app = "shop", services = mapOf("postgres" to ExportedService(mapOf("version" to "17")))))
        assertFalse(p.exists)
        val opts =
            p.request.services
                .getValue("postgres")
                .options
        assertEquals("17", opts["version"])
        assertEquals("shop", opts["name"])
        assertEquals("false", opts["migrate_on_up"])
        assertEquals(
            engine
                .appDir("shop")
                .resolve("migrations/postgres")
                .toAbsolutePath()
                .toString(),
            opts["migrations"],
        )
        assertTrue(opts.getValue("password").length >= 24)
        assertEquals(emptyList(), p.keptSecrets)
    }

    @Test
    fun `importing over an existing app keeps its secrets`() {
        engine.create("shop", services = listOf("postgres"))
        engine.add("shop", "postgres", mapOf("password" to "local-pw"))
        val p = preview(file(app = "shop", services = mapOf("postgres" to ExportedService())))
        assertTrue(p.exists)
        assertEquals(listOf("postgres.password"), p.keptSecrets)
        Importer(engine).apply(p.request)
        assertEquals("local-pw", engine.serviceOptions(engine.loadApp("shop"), "postgres")["password"])
    }

    @Test
    fun `renaming on import leaves the existing app alone`() {
        engine.create("shop", services = listOf("valkey"))
        val p = preview(file(app = "shop", services = mapOf("postgres" to ExportedService())), asName = "shop2")
        assertFalse(p.exists)
        Importer(engine).apply(p.request)
        assertEquals(listOf("valkey"), engine.loadApp("shop").services.map { it.id })
        assertEquals(listOf("postgres"), engine.loadApp("shop2").services.map { it.id })
    }

    @Test
    fun `services missing from the file are removed and their ports released`() {
        engine.create("shop", services = listOf("postgres", "valkey"))
        val pgPorts = engine.loadApp("shop").service("postgres")!!.ports
        val p = preview(file(app = "shop", services = mapOf("postgres" to ExportedService())))
        val app = Importer(engine).apply(p.request)
        assertEquals(listOf("postgres"), app.services.map { it.id })
        assertEquals(listOf("postgres"), engine.loadApp("shop").services.map { it.id })
        assertEquals(pgPorts, engine.loadApp("shop").service("postgres")!!.ports)
        assertFalse("shop/valkey/" in ports())
    }

    @Test
    fun `apply stores only options that differ from the default, plus secrets`() {
        val p = preview(file(app = "shop", services = mapOf("postgres" to ExportedService(mapOf("version" to "17", "name" to "shop")))))
        Importer(engine).apply(p.request)
        val stored = storedOptions("shop", "postgres")
        assertEquals(setOf("version", "password"), stored.keys)
        assertEquals(
            p.request.services
                .getValue("postgres")
                .options["password"],
            stored["password"],
        )
    }

    @Test
    fun `env names are applied`() {
        engine.create("probe", services = listOf("postgres"))
        val key = engine.envEntries(engine.loadApp("probe")).first { it.key.endsWith("HOST") }.key
        val p = preview(file(app = "shop", services = mapOf("postgres" to ExportedService()), envNames = mapOf(key to "PGHOST")))
        assertEquals(mapOf(key to "PGHOST"), p.request.envNames)
        assertEquals(mapOf(key to "PGHOST"), Importer(engine).apply(p.request).envNames)
    }

    @Test
    fun `source merges per field and drops a stale password`() {
        engine.create("shop", services = listOf("postgres"))
        engine.setSource("shop", "postgres", SourceSettings("stage", 5432, "shop", "me"))
        engine.secrets.setPassword("shop", "postgres", "saved-pw")

        val same = preview(file(services = mapOf("postgres" to ExportedService(source = SourceSettings("stage", 5432, "shop", "")))))
        assertEquals(
            SourceSettings("stage", 5432, "shop", ""),
            same.request.services
                .getValue("postgres")
                .source,
        )
        Importer(engine).apply(same.request)
        assertEquals(SourceSettings("stage", 5432, "shop", "me"), engine.loadApp("shop").service("postgres")!!.source)
        assertEquals("saved-pw", engine.secrets.password("shop", "postgres"))

        val moved = preview(file(services = mapOf("postgres" to ExportedService(source = SourceSettings("prod", 5432, "shop", "")))))
        Importer(engine).apply(moved.request)
        assertEquals(SourceSettings("prod", 5432, "shop", ""), engine.loadApp("shop").service("postgres")!!.source)
        assertNull(engine.secrets.password("shop", "postgres"))
    }

    @Test
    fun `an applied request with an empty user keeps the saved user and password`() {
        engine.create("shop", services = listOf("postgres"))
        engine.setSource("shop", "postgres", SourceSettings("stage", 5432, "shop", "me"))
        engine.secrets.setPassword("shop", "postgres", "saved-pw")
        val request = preview(file(services = mapOf("postgres" to ExportedService()))).request
        val edited =
            request.copy(
                services =
                    mapOf(
                        "postgres" to request.services.getValue("postgres").copy(source = SourceSettings("stage", 5432, "shop", "")),
                    ),
            )

        Importer(engine).apply(edited)

        assertEquals(SourceSettings("stage", 5432, "shop", "me"), engine.loadApp("shop").service("postgres")!!.source)
        assertEquals("saved-pw", engine.secrets.password("shop", "postgres"))
    }

    @Test
    fun `a file user replaces the saved one when the database is the same`() {
        engine.create("shop", services = listOf("postgres"))
        engine.setSource("shop", "postgres", SourceSettings("stage", 5432, "shop", "me"))
        engine.secrets.setPassword("shop", "postgres", "saved-pw")
        val p = preview(file(services = mapOf("postgres" to ExportedService(source = SourceSettings("stage", 5432, "shop", "ci")))))
        Importer(engine).apply(p.request)
        assertEquals(
            "ci",
            engine
                .loadApp("shop")
                .service("postgres")!!
                .source!!
                .user,
        )
        assertEquals("saved-pw", engine.secrets.password("shop", "postgres"))
    }

    @Test
    fun `repo-relative migrations resolve against a found checkout`() {
        val home = Files.createTempDirectory("home")
        Files.createDirectories(home.resolve("code/shop-service/db"))
        val p = preview(file(services = mapOf("postgres" to ExportedService(migrations = ExportedPath("shop-service", "db")))), home = home)
        assertEquals(
            home.resolve("code/shop-service/db").toString(),
            p.request.services
                .getValue("postgres")
                .options["migrations"],
        )
        assertEquals(home.resolve("code/shop-service").toString(), p.checkoutGuesses["postgres"])
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun `an unknown checkout leaves migrations empty with a warning, and applies as the default`() {
        val p = preview(file(services = mapOf("postgres" to ExportedService(migrations = ExportedPath("shop-service", "db")))))
        assertEquals(
            "",
            p.request.services
                .getValue("postgres")
                .options["migrations"],
        )
        assertTrue(p.warnings.single().contains("shop-service"))
        Importer(engine).apply(p.request)
        assertFalse("migrations" in storedOptions("shop", "postgres"))
    }

    @Test
    fun `absolute migrations paths are used as given`() {
        val dir = Files.createTempDirectory("loose")
        val p = preview(file(services = mapOf("postgres" to ExportedService(migrations = ExportedPath(null, dir.toString())))))
        assertEquals(
            dir.toString(),
            p.request.services
                .getValue("postgres")
                .options["migrations"],
        )
    }

    @Test
    fun `validate reports bad names, unknown services and bad booleans`() {
        val request =
            ImportRequest(
                app = "Bad_Name",
                services =
                    mapOf(
                        "mongo" to ImportedService(emptyMap()),
                        "valkey" to ImportedService(mapOf("persist" to "maybe")),
                    ),
                envNames = emptyMap(),
            )
        val messages = Importer(engine).validate(request)
        assertEquals(3, messages.size, messages.toString())
        assertTrue(messages.any { "Bad_Name" in it })
        assertTrue(messages.any { "mongo" in it })
        assertTrue(messages.any { "maybe" in it })
        val error = assertFailsWith<GantryException> { Importer(engine).apply(request) }
        assertTrue("mongo" in error.message!!)
        assertFalse(Files.exists(engine.appDir("Bad_Name")))
    }

    @Test
    fun `validate reports unknown options and env names`() {
        val request =
            ImportRequest(
                app = "shop",
                services = mapOf("valkey" to ImportedService(mapOf("colour" to "red"))),
                envNames = mapOf("NOPE" to "X"),
            )
        val messages = Importer(engine).validate(request)
        assertEquals(2, messages.size, messages.toString())
        assertTrue(messages.any { "colour" in it })
        assertTrue(messages.any { "NOPE" in it })
    }

    @Test
    fun `a leftover volume of a new database is warned about`() {
        val docker = home.resolve("fake-docker")
        Files.writeString(docker, "#!/bin/sh\n[ \"\$1 \$2 \$3\" = \"volume inspect gantry-shop_postgres-data\" ]\n")
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwx------"))
        val withVolume = Engine(home = home, dockerCommand = docker.toString())
        val p = Importer(withVolume).preview(file(services = mapOf("postgres" to ExportedService())), home = nobody, cwd = nobody)
        assertTrue(p.warnings.single().contains("gantry-shop_postgres-data"))
        assertTrue(p.warnings.single().contains("destroy the app, or set the password option to the volume's old one"))
        assertFalse(p.warnings.single().contains("sync"))
        assertTrue(withVolume.docker.volumeExists("gantry-shop_postgres-data"))
        assertFalse(withVolume.docker.volumeExists("gantry-shop_mysql-data"))
        assertFalse(engine.docker.volumeExists("gantry-shop_postgres-data"))
    }

    @Test
    fun `replaceApp creates, then replaces keeping ports of remaining services`() {
        engine.replaceApp(App("shop", listOf(ServiceState("postgres"), ServiceState("valkey"))))
        val pgPorts = engine.loadApp("shop").service("postgres")!!.ports
        assertTrue("shop/valkey/" in ports())
        val replaced = engine.replaceApp(App("shop", listOf(ServiceState("postgres", mapOf("version" to "17")))))
        assertEquals(pgPorts, replaced.service("postgres")!!.ports)
        assertFalse("shop/valkey/" in ports())
        assertNotEquals(emptyMap(), replaced.service("postgres")!!.ports)
    }
}
