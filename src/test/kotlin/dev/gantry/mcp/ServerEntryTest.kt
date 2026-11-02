package dev.gantry.mcp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerEntryTest {
    @Test
    fun `entry runs this install with its java and docker`() {
        val entry =
            serverEntry(
                "shop",
                Path.of("/g/bin/gantry"),
                javaHome = "/jdk",
                docker = "/usr/local/bin/docker",
                env =
                    mapOf(
                        "GANTRY_HOME" to "/h",
                        "DOCKER_CONTEXT" to "colima",
                        "DOCKER_CONFIG" to "/cfg",
                        "DOCKER_CERT_PATH" to "/certs",
                        "DOCKER_TLS_VERIFY" to "1",
                        "DB_PASSWORD" to "secret",
                    ),
            )

        assertEquals("gantry-shop", entry.name)
        assertEquals("/g/bin/gantry", entry.command)
        assertEquals(listOf("mcp", "serve", "shop"), entry.args)
        assertEquals(
            mapOf(
                "JAVA_HOME" to "/jdk",
                "GANTRY_HOME" to "/h",
                "GANTRY_DOCKER" to "/usr/local/bin/docker",
                "DOCKER_CONTEXT" to "colima",
                "DOCKER_CONFIG" to "/cfg",
                "DOCKER_CERT_PATH" to "/certs",
                "DOCKER_TLS_VERIFY" to "1",
            ),
            entry.env,
        )
    }

    @Test
    fun `a windows batch launcher is started through cmd`() {
        val entry = serverEntry("shop", Path.of("C:\\g\\bin\\gantry.bat"), javaHome = "C:\\jdk", docker = null, env = emptyMap())

        assertEquals("cmd", entry.command)
        assertEquals(listOf("/c", "C:\\g\\bin\\gantry.bat", "mcp", "serve", "shop"), entry.args)
    }

    @Test
    fun `a stale entry names what moved`() {
        val install = Files.createTempDirectory("gantry-install")
        val launcher = Files.createDirectories(install.resolve("bin")).resolve("gantry").also { Files.createFile(it) }
        val jdk = Files.createDirectories(install.resolve("jdk/bin")).parent.also { Files.createFile(it.resolve("bin/java")) }

        assertNull(staleness(serverEntry("shop", launcher, jdk.toString(), docker = null, env = emptyMap())))
        val movedGantry = staleness(serverEntry("shop", install.resolve("gone/gantry"), jdk.toString(), docker = null, env = emptyMap()))
        assertTrue("gone/gantry" in movedGantry!!)
        val movedJava = staleness(serverEntry("shop", launcher, "/no/such/jdk", docker = null, env = emptyMap()))
        assertTrue("/no/such/jdk" in movedJava!!)
        val movedDocker = staleness(serverEntry("shop", launcher, jdk.toString(), docker = "/no/docker", env = emptyMap()))
        assertTrue("/no/docker" in movedDocker!!)
        val windows = serverEntry("shop", install.resolve("gone.bat"), jdk.toString(), docker = null, env = emptyMap())
        assertTrue("gone.bat" in staleness(windows)!!, "the launcher of a cmd /c entry is checked")
    }

    @Test
    fun `a managed wrapper is used as it is, without resolving links`() {
        val dir = Files.createTempDirectory("gantry-wrapper")
        val real = Files.createFile(dir.resolve("real-gantry"))
        val wrapper = Files.createSymbolicLink(dir.resolve("gantry"), real)

        assertEquals(wrapper, locateLauncher(jar = null, windows = false, managed = wrapper.toString()))
        assertNull(locateLauncher(jar = null, windows = false, managed = dir.resolve("missing").toString()))
    }

    @Test
    fun `an entry for a managed wrapper leaves Java to the wrapper`() {
        val entry = serverEntry("shop", Path.of("/home/me/.gantry/bin/gantry"), javaHome = null, docker = null, env = emptyMap())

        assertEquals(emptyMap(), entry.env)
    }

    @Test
    fun `entry omits unset variables`() {
        val entry = serverEntry("shop", Path.of("/g/bin/gantry"), javaHome = "/jdk", docker = null, env = emptyMap())

        assertEquals(setOf("JAVA_HOME"), entry.env.keys)
    }

    @Test
    fun `launcher is found next to the jar`() {
        val install = Files.createTempDirectory("gantry-install")
        val jar = Files.createDirectories(install.resolve("lib")).resolve("gantry.jar").also { Files.createFile(it) }
        assertNull(locateLauncher(jar, windows = false), "no bin directory yet")

        val bin = Files.createDirectories(install.resolve("bin"))
        Files.createFile(bin.resolve("gantry"))
        Files.createFile(bin.resolve("gantry.bat"))

        assertEquals(bin.resolve("gantry").toRealPath(), locateLauncher(jar, windows = false))
        assertEquals(bin.resolve("gantry.bat").toRealPath(), locateLauncher(jar, windows = true))
        assertNull(locateLauncher(null, windows = false), "not running from a jar")
    }

    @Test
    fun `docker is resolved from PATH`() {
        val dir = Files.createTempDirectory("gantry-path")
        val docker = dir.resolve("docker").also { Files.createFile(it).toFile().setExecutable(true) }

        assertEquals(docker.toString(), resolveDocker("docker", path = "/nowhere:$dir", windows = false))
        assertEquals("/x/docker", resolveDocker("/x/docker", path = "", windows = false))
        assertNull(resolveDocker("docker", path = "", windows = false))
        assertNull(resolveDocker("docker", path = null, windows = false))
    }
}
