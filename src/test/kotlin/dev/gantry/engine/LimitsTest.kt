package dev.gantry.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LimitsTest {
    private val home: Path = Files.createTempDirectory("gantry-limits")
    private val engine = Engine(home = home, dockerCommand = NO_DOCKER, runtimeLib = TEST_RUNTIME_LIB)

    private val header = Regex("(?m)^  ([a-zA-Z0-9_-]+):\\s*$")

    /** Each compose service's body, by name. */
    private fun services(app: String): Map<String, String> {
        val compose = engine.appDir(app).resolve("compose.yml").readText()
        val section = compose.substringAfter("\nservices:\n").substringBefore("\nvolumes:\n")
        val headers = header.findAll(section).toList()
        return headers.zip(headers.drop(1).map { it.range.first } + section.length).associate { (match, end) ->
            match.groupValues[1] to section.substring(match.range.first, end)
        }
    }

    @Test
    fun `every container gets a cpu and memory ceiling`() {
        engine.create("shop", services = listOf("postgres", "mysql", "temporal", "valkey", "dynamodb", "elasticmq", "kafka", "gateway"))

        val services = services("shop")
        assertTrue(services.size > 15, "expected the whole stack, got ${services.keys}")
        services.forEach { (name, body) ->
            assertTrue(Regex("(?m)^    cpus: [0-9.]+$").containsMatchIn(body), "$name has no cpus limit")
            assertTrue(Regex("(?m)^    mem_limit: [0-9]+m$").containsMatchIn(body), "$name has no mem_limit")
        }
    }

    @Test
    fun `the query-log proxy and its database get their own ceilings`() {
        engine.create("shop", services = listOf("postgres"))
        engine.add("shop", "postgres", mapOf("query_log" to "true"))

        val services = services("shop")
        assertTrue("    cpus: 0.5\n    mem_limit: 768m\n" in services.getValue("postgres"))
        assertTrue("    cpus: 1.5\n    mem_limit: 1024m\n" in services.getValue("postgres-db"))
    }

    @Test
    fun `valkey's ceiling leaves room above its maxmemory`() {
        engine.create("shop", services = listOf("valkey"))
        engine.add("shop", "valkey", mapOf("maxmemory" to "2gb"))

        assertTrue("    mem_limit: ${2048 + 256}m\n" in services("shop").getValue("valkey"))
    }

    @Test
    fun `a limit the template sets itself is kept`() {
        val fragment = "  custom:\n    image: x\n    mem_limit: 4g\n"

        assertEquals(fragment, withResourceLimits(fragment, mapOf("service" to "custom")))
    }

    @Test
    fun `sync runs are capped`() {
        assertEquals(listOf("--cpus=1", "--memory=1024m"), SYNC_LIMIT.runFlags())
    }
}
