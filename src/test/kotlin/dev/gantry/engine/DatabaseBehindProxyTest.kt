package dev.gantry.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseBehindProxyTest {
    private val tools: Path = Files.createTempDirectory("gantry-fake-docker")
    private val calls = tools.resolve("docker.log")
    private val ps = tools.resolve("ps.json").also { it.writeText("") }

    private val engine =
        tools.resolve("docker").let { docker ->
            docker.writeText(
                "#!/bin/sh\necho \"\$@\" >> '$calls'\n" +
                    "case \"\$*\" in *' ps -a --format json'*) cat '$ps' ;; esac\nexit 0\n",
            )
            Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwx------"))
            Engine(home = tools.resolve("home"), dockerCommand = docker.toString(), runtimeLib = TEST_RUNTIME_LIB)
        }

    private fun logsArgs(service: String): String {
        calls.writeText("")
        engine.docker.logs("shop", service, 10)
        return calls
            .readText()
            .lines()
            .single { " logs " in " $it " }
            .substringAfter("logs ")
    }

    @Test
    fun `logs of a query-logged database show the database and its proxy, and the database can be named`() {
        engine.create("shop", services = listOf("postgres", "mysql"))
        engine.add("shop", "mysql", mapOf("query_log" to "false"))

        assertTrue(logsArgs("postgres").endsWith("--tail 10 postgres-db postgres"), logsArgs("postgres"))
        assertTrue(logsArgs("postgres-db").endsWith("--tail 10 postgres-db"), logsArgs("postgres-db"))
        assertTrue(logsArgs("mysql").endsWith("--tail 10 mysql"), logsArgs("mysql"))
    }

    @Test
    fun `status shows the worse of the proxy and its database`() {
        engine.create("shop", services = listOf("postgres", "mysql"))
        ps.writeText(
            """
            {"Service":"postgres","State":"running","Health":"healthy"}
            {"Service":"postgres-db","State":"restarting","Health":"unhealthy"}
            {"Service":"mysql","State":"running","Health":"healthy"}
            {"Service":"mysql-db","State":"running","Health":"starting"}
            """.trimIndent(),
        )
        val services =
            engine.docker
                .status("shop")
                .services
                .associateBy { it.id }

        assertEquals("restarting" to "unhealthy", services.getValue("postgres").let { it.state to it.health })
        assertEquals("running" to "starting", services.getValue("mysql").let { it.state to it.health })
        assertEquals(setOf("postgres", "mysql"), services.keys)
    }

    @Test
    fun `a running proxy and database read as healthy`() {
        engine.create("shop", services = listOf("postgres"))
        ps.writeText(
            """
            {"Service":"postgres","State":"running","Health":"healthy"}
            {"Service":"postgres-db","State":"running","Health":"healthy"}
            """.trimIndent(),
        )
        val pg =
            engine.docker
                .status("shop")
                .services
                .single()
        assertEquals("running" to "healthy", pg.state to pg.health)
    }
}
