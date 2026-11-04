package dev.gantry.share

import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.NO_DOCKER
import dev.gantry.engine.SourceSettings
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
}
