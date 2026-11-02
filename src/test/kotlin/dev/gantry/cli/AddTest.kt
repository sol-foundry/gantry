package dev.gantry.cli

import com.github.ajalt.clikt.testing.test
import dev.gantry.engine.Engine
import dev.gantry.engine.NO_DOCKER
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AddTest {
    private val engine =
        Engine(home = Files.createTempDirectory("gantry-cli-test"), dockerCommand = NO_DOCKER).apply {
            create("my-app", services = listOf("postgres"))
        }

    private fun services() =
        engine
            .readApp("my-app")
            .services
            .map { it.id }
            .toSet()

    @Test
    fun `the service is named with --service`() {
        val result = Add(engine).test("my-app --service temporal")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(setOf("postgres", "temporal"), services())
    }

    @Test
    fun `--service works with --set`() {
        Add(engine).test("my-app --service valkey --set persist=true")

        assertEquals("true", engine.serviceOptions(engine.readApp("my-app"), "valkey")["persist"])
    }

    @Test
    fun `the service given without the flag still works`() {
        Add(engine).test("my-app temporal")

        assertTrue("temporal" in services())
    }

    @Test
    fun `naming no service is an error`() {
        val result = Add(engine).test("my-app")

        assertNotEquals(0, result.statusCode)
        assertTrue("--service" in result.output)
    }
}
