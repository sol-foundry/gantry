package dev.gantry.share

import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.NO_DOCKER
import dev.gantry.engine.SourceSettings
import dev.gantry.gateway.PushedAuthorizer
import dev.gantry.gateway.saveGatewayConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ExporterTest {
    private val engine = Engine(home = Files.createTempDirectory("gantry-export"), dockerCommand = NO_DOCKER)

    @Test
    fun `secrets and ports are left out, name and user are pinned`() {
        engine.create("shop", services = listOf("postgres", "valkey"))
        engine.add("shop", "postgres", mapOf("password" to "hunter2", "version" to "17"))
        val file = Exporter(engine).export("shop")
        val pg = file.services.getValue("postgres")
        assertFalse("password" in pg.options)
        assertEquals("17", pg.options["version"])
        assertEquals("shop", pg.options["name"])
        assertEquals("shop", pg.options["user"])
        assertFalse(file.encode().contains("hunter2"))
        assertFalse(file.encode().contains("\"ports\""))
    }

    @Test
    fun `env names and source settings are exported`() {
        engine.create("shop", services = listOf("postgres"))
        val source = SourceSettings("stage", 5432, "shop", "me")
        engine.setSource("shop", "postgres", source)
        val key = engine.envEntries(engine.loadApp("shop")).first { it.key.endsWith("HOST") }.key
        engine.setEnvNames("shop", mapOf(key to "PGHOST"))
        val file = Exporter(engine).export("shop")
        assertEquals(mapOf(key to "PGHOST"), file.envNames)
        assertEquals(source, file.services.getValue("postgres").source)
    }

    @Test
    fun `renames left by a removed service are not exported`() {
        engine.create("shop", services = listOf("postgres", "elasticmq"))
        engine.setEnvNames("shop", mapOf("DB_USER" to "DB_USERNAME", "AWS_ENDPOINT_URL_SQS" to "SQS_ENDPOINT_OVERRIDE"))
        engine.remove("shop", "elasticmq")

        // Kept with the app (adding the queue back restores it), but an importer would reject it.
        assertEquals("SQS_ENDPOINT_OVERRIDE", engine.loadApp("shop").envNames["AWS_ENDPOINT_URL_SQS"])
        assertEquals(mapOf("DB_USER" to "DB_USERNAME"), Exporter(engine).export("shop").envNames)
    }

    @Test
    fun `a custom migrations dir is exported, the default is not`() {
        val repo = Files.createTempDirectory("svc")
        Files.createDirectories(repo.resolve(".git"))
        val inRepo = repo.resolve("db/migration").also(Files::createDirectories)
        engine.create("shop", services = listOf("postgres"))
        assertNull(
            Exporter(engine)
                .export("shop")
                .services
                .getValue("postgres")
                .migrations,
        )
        engine.add("shop", "postgres", mapOf("migrations" to inRepo.toString()))
        val file = Exporter(engine).export("shop")
        val pg = file.services.getValue("postgres")
        assertEquals(ExportedPath(repo.fileName.toString(), "db/migration"), pg.migrations)
        assertFalse("migrations" in pg.options)
    }

    @Test
    fun `migrations path export cases`() {
        val repo = Files.createTempDirectory("svc")
        Files.createDirectories(repo.resolve(".git"))
        val inRepo = repo.resolve("src/main/resources/db/migration").also(Files::createDirectories)
        val outside = Files.createTempDirectory("loose")
        val default = engine.appDir("shop").resolve("migrations/postgres")
        val e = Exporter(engine)
        assertEquals(ExportedPath(repo.fileName.toString(), "src/main/resources/db/migration"), e.exportPath(inRepo, default))
        assertEquals(ExportedPath(null, outside.toString()), e.exportPath(outside, default))
        assertNull(e.exportPath(default, default))
    }

    @Test
    fun `unknown app fails`() {
        assertFailsWith<GantryException> { Exporter(engine).export("nope") }
    }

    @Test
    fun `export includes gateway config and authorizer dirs, never secret values`() {
        val lib = Files.createTempDirectory("gantry-lib").also { Files.writeString(it.resolve("gantry.jar"), "jar") }
        val engine = Engine(home = Files.createTempDirectory("gantry-export"), dockerCommand = NO_DOCKER, gatewayLib = lib)
        engine.create("shop", services = listOf("gateway"))
        val config = "default:\n  auth: public\n  headers:\n    x-key: \"{{secret:API_KEY}}\"\n"
        assertEquals(emptyList(), engine.saveGatewayConfig("shop", config))
        engine.secrets.setSecret("shop", "API_KEY", "super-secret-value")
        val repo = Files.createDirectories(Files.createTempDirectory("repos").resolve("my-api"))
        Files.createDirectories(repo.resolve(".git"))
        val loose = Files.createTempDirectory("loose")
        engine.setAuthorizers(
            "shop",
            listOf(
                PushedAuthorizer("check", "my-api/auth/check", 9001, "gantry-shop-auth-check:latest"),
                PushedAuthorizer("solo", loose.toString(), image = "gantry-shop-auth-solo:latest"),
            ),
        )

        val file = Exporter(engine).export("shop")

        val gateway = file.gateway!!
        assertEquals(config, gateway.config)
        assertEquals(
            listOf(
                ExportedAuthorizer("check", ExportedPath("my-api", "auth/check"), 9001),
                ExportedAuthorizer("solo", ExportedPath(null, loose.toString()), 8080),
            ),
            gateway.authorizers,
        )
        val text = file.encode()
        assertFalse("super-secret-value" in text)
        assertFalse("gantry-shop-auth-check:latest" in text)
        assertEquals(file, parseExportFile(text))
    }

    @Test
    fun `an app without a gateway exports none`() {
        engine.create("shop", services = listOf("valkey"))
        val file = Exporter(engine).export("shop")
        assertNull(file.gateway)
        assertFalse("\"gateway\"" in file.encode())
    }
}
