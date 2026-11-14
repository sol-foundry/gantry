package dev.gantry.cli

import com.github.ajalt.clikt.testing.test
import dev.gantry.engine.Engine
import dev.gantry.engine.NO_DOCKER
import dev.gantry.engine.TEST_RUNTIME_LIB
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CreateTest {
    private val engine =
        Engine(home = Files.createTempDirectory("gantry-cli-test"), dockerCommand = NO_DOCKER, runtimeLib = TEST_RUNTIME_LIB)

    private fun services(app: String) =
        engine
            .readApp(app)
            .services
            .map { it.id }
            .toSet()

    @Test
    fun `services are named with --services`() {
        val result = Create(engine).test("my-app --services postgres valkey elasticmq")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(setOf("postgres", "valkey", "elasticmq"), services("my-app"))
    }

    @Test
    fun `-s is the short form and options can follow the list`() {
        val result = Create(engine).test("my-app -s kafka --preset fullstack")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(true, "kafka" in services("my-app") && "postgres" in services("my-app"), "both the list and the preset apply")
    }

    @Test
    fun `services given without the flag still work`() {
        Create(engine).test("my-app postgres valkey")

        assertEquals(setOf("postgres", "valkey"), services("my-app"))
    }
}
