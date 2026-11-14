package dev.gantry.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DockerExecTest {
    private val dir: Path = Files.createTempDirectory("gantry-docker-exec-test")
    private val received: Path = dir.resolve("stdin")

    /** A docker whose `inspect` runs [inspect] and whose `exec` runs [exec] (stdin is saved first unless [exec] hangs). */
    private fun docker(
        inspect: String,
        exec: String = "cat > $received",
    ): Docker {
        val script = dir.resolve("docker")
        script.writeText("#!/bin/sh\ncase \"$1\" in\n  inspect) $inspect ;;\n  exec) $exec ;;\nesac\n")
        script.toFile().setExecutable(true)
        return Engine(home = dir.resolve("home"), dockerCommand = script.toString()).docker
    }

    @Test
    fun `pipes the input into a running container`() {
        val result = docker(inspect = "echo true").execWithInput("shop-gateway", listOf("sh", "-c", "cat"), "line\n".toByteArray())

        assertEquals(ExecResult.DONE, result)
        assertEquals("line\n", received.readText())
    }

    @Test
    fun `a stopped or missing container is not running`() {
        assertEquals(ExecResult.NOT_RUNNING, docker(inspect = "echo false").execWithInput("c", listOf("true"), ByteArray(0)))
        assertEquals(
            ExecResult.NOT_RUNNING,
            docker(inspect = "echo 'Error: No such container: c' >&2; exit 1").execWithInput("c", listOf("true"), ByteArray(0)),
        )
        assertEquals(
            ExecResult.NOT_RUNNING,
            Engine(home = dir, dockerCommand = NO_DOCKER).docker.execWithInput("c", listOf("true"), ByteArray(0)),
        )
    }

    @Test
    fun `a daemon error is a failure, never a stopped container`() {
        val result =
            docker(inspect = "echo 'Cannot connect to the Docker daemon' >&2; exit 1").execWithInput("c", listOf("true"), ByteArray(0))

        assertEquals(ExecResult.FAILED, result)
    }

    @Test
    fun `a failing exec is a failure`() {
        assertEquals(
            ExecResult.FAILED,
            docker(inspect = "echo true", exec = "cat >/dev/null; exit 3").execWithInput("c", listOf("true"), ByteArray(0)),
        )
    }

    @Test
    fun `a stalled exec that stops reading stdin still times out`() {
        val docker = docker(inspect = "echo true", exec = "sleep 60")
        docker.execTimeout = java.time.Duration.ofSeconds(1)
        val started = System.nanoTime()

        val result = docker.execWithInput("c", listOf("true"), ByteArray(4 * 1024 * 1024))

        assertEquals(ExecResult.FAILED, result)
        assertTrue(System.nanoTime() - started < 10_000_000_000L, "returned after the timeout instead of blocking on stdin")
    }
}
