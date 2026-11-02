package dev.gantry.mcp

import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.NO_DOCKER
import dev.gantry.engine.SourceSettings
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionDetailsTest {
    private val engine = Engine(home = Files.createTempDirectory("gantry-mcp-test"), dockerCommand = NO_DOCKER)

    @Test
    fun `lists each service with its mapped env`() {
        engine.create("shop", services = listOf("postgres", "elasticmq"))
        engine.setEnvNames("shop", mapOf("DB_HOST" to "PG_HOST", "DATABASE_URL" to ""))

        val details = connectionDetails(engine, "shop")

        assertEquals(listOf("postgres", "elasticmq"), details.services.map { it.id })
        val postgres = details.services.first { it.id == "postgres" }
        assertTrue("PG_HOST" in postgres.env)
        assertFalse("DB_HOST" in postgres.env)
        assertFalse("DATABASE_URL" in postgres.env, "omitted variables are left out")
        assertEquals(engine.envText(engine.readApp("shop")), details.env)
    }

    @Test
    fun `host and ports are given even when every variable is omitted`() {
        engine.create("shop", services = listOf("valkey"))
        engine.setEnvNames("shop", mapOf("CACHE_HOST" to "", "CACHE_PORT" to "", "CACHE_URL" to "", "REDIS_URL" to ""))
        val ports = engine.readApp("shop").service("valkey")!!.ports

        val cache = connectionDetails(engine, "shop").services.single()

        assertEquals(emptyMap(), cache.env)
        assertEquals("localhost", cache.host)
        assertEquals(ports, cache.ports)
        assertTrue("main" in cache.ports)
    }

    @Test
    fun `connection values are given even when the app omits their variables`() {
        engine.create("shop", services = listOf("postgres"))
        engine.setEnvNames("shop", mapOf("DB_NAME" to "", "DATABASE_URL" to "", "DB_USER" to "DB_USERNAME"))

        val postgres = connectionDetails(engine, "shop").services.single()

        assertFalse("DB_NAME" in postgres.env)
        assertEquals("shop", postgres.connection["DB_NAME"])
        assertTrue(postgres.connection.getValue("DATABASE_URL").startsWith("postgres"))
        assertEquals(postgres.env["DB_USERNAME"], postgres.connection["DB_USER"], "connection uses the catalog's own keys")
        assertTrue("DB_NAME=shop" in connectionDetails(engine, "shop").toText())
    }

    @Test
    fun `queues include their dead-letter queues`() {
        engine.create("shop", services = listOf("elasticmq"))
        engine.add("shop", "elasticmq", mapOf("queues" to "orders,jobs:60:jobs-dead"))

        val queue = connectionDetails(engine, "shop").services.single()

        assertEquals(listOf(QueueInfo("orders", "orders-dlq"), QueueInfo("jobs", "jobs-dead")), queue.queues)
        assertTrue(queue.notes.any { "any AWS access key" in it })
    }

    @Test
    fun `kafka topics are listed`() {
        engine.create("shop", services = listOf("kafka"))
        engine.add("shop", "kafka", mapOf("topics" to "events:3,audit"))

        assertEquals(listOf("events", "audit"), connectionDetails(engine, "shop").services.single().topics)
    }

    @Test
    fun `a missing docker binary points at re-running install`() {
        engine.create("shop", services = listOf("postgres", "valkey"))

        val details = connectionDetails(engine, "shop")

        assertEquals("unreachable", details.docker)
        assertTrue(details.services.all { it.state == "unknown" })
        assertTrue(NO_DOCKER in details.hint!!)
        assertTrue("gantry mcp install shop" in details.hint)
    }

    /** An engine whose docker is a shell script answering `version` and `compose` as given. */
    private fun fakeDocker(
        version: String,
        compose: String,
    ): Engine {
        val script = Files.createTempDirectory("gantry-fake-docker").resolve("docker")
        script.writeText("#!/bin/sh\ncase \"$1\" in\n  version) $version ;;\n  compose) $compose ;;\nesac\n")
        script.toFile().setExecutable(true)
        return Engine(home = engine.home, dockerCommand = script.toString()).also { it.create("shop", services = listOf("postgres")) }
    }

    @Test
    fun `a daemon that is down says so`() {
        val details = connectionDetails(fakeDocker(version = "echo 'Cannot connect' >&2; exit 1", compose = "exit 0"), "shop")

        assertEquals("unreachable", details.docker)
        assertTrue("Docker running" in details.hint!!)
    }

    @Test
    fun `a docker that hangs is given up on`() {
        val hung = fakeDocker(version = "sleep 5", compose = "exit 0")
        hung.docker.queryTimeout = java.time.Duration.ofMillis(300)

        val started = System.nanoTime()
        val details = connectionDetails(hung, "shop")

        assertTrue((System.nanoTime() - started) < 3_000_000_000L, "returns well before the hung command would")
        assertTrue("didn't answer" in details.hint!!)
        assertTrue(details.services.all { it.state == "unknown" })
    }

    @Test
    fun `a stopped app points at gantry start`() {
        val details = connectionDetails(fakeDocker(version = "echo 27.0", compose = "exit 0"), "shop")

        assertTrue(details.services.all { it.state == "not created" })
        assertEquals("The app is not running. Start it with: gantry start shop", details.hint)
    }

    @Test
    fun `a failing docker compose leaves states unknown, not stopped`() {
        val details = connectionDetails(fakeDocker(version = "echo 27.0", compose = "echo 'unknown command: compose' >&2; exit 1"), "shop")

        assertEquals("reachable", details.docker)
        assertTrue(details.services.all { it.state == "unknown" })
        assertTrue("docker compose" in details.hint!!)
    }

    @Test
    fun `sync source is never exposed`() {
        engine.create("shop", services = listOf("postgres"))
        engine.setSource("shop", "postgres", SourceSettings(host = "stage.example", port = 5432, database = "shop", user = "me"))

        assertFalse("stage.example" in Json.encodeToString(connectionDetails(engine, "shop")))
    }

    @Test
    fun `missing app names gantry create`() {
        val e = assertFailsWith<GantryException> { connectionDetails(engine, "ghost") }
        assertTrue("gantry create" in e.message!!)
    }
}
