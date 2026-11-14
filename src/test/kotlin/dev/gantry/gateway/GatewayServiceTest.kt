package dev.gantry.gateway

import dev.gantry.engine.App
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.NO_DOCKER
import dev.gantry.engine.ServiceState
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GatewayServiceTest {
    private val home: Path = Files.createTempDirectory("gantry-gateway")
    private val lib: Path =
        Files.createTempDirectory("gantry-lib").also {
            it.resolve("gantry-0.0.0.jar").writeText("jar bytes")
            it.resolve("kaml.jar").writeText("more bytes")
        }
    private val engine = Engine(home = home, dockerCommand = NO_DOCKER, gatewayLib = lib)
    private val dir get() = engine.appDir("shop")

    private val withSecrets =
        """
        authorizers:
          s:
            type: supabase
            url: https://x.supabase.co
            jwtSecret: "{{secret:SUPABASE_JWT}}"
        routes:
          - name: api
            match:
              path: /api/**
            auth: s
            headers:
              x-internal-api-key: "{{secret:INTERNAL_API_KEY}}"
        """.trimIndent()

    @Test
    fun `adding a gateway writes a default gateway yml once`() {
        engine.create("shop", services = listOf("gateway"))
        val file = dir.resolve("gateway.yml")
        assertEquals(DEFAULT_GATEWAY_YML, file.readText())

        file.writeText("upstream: http://localhost:9999\n")
        engine.add("shop", "gateway", mapOf("upstream" to "http://localhost:7000"))
        engine.refresh("shop")
        assertEquals("upstream: http://localhost:9999\n", file.readText())
    }

    @Test
    fun `render produces apisix files from gateway yml`() {
        engine.create("shop", services = listOf("gateway"))
        val config = dir.resolve("config.yaml").readText()
        assertContains(config, "\"config_provider\": \"yaml\"")
        val routes = dir.resolve("apisix.yaml").readText()
        assertTrue(routes.endsWith("#END\n"))
        // The default upstream option is localhost:8080, reached from the container through the host gateway.
        assertContains(routes, "\"host.docker.internal:8080\": 1")

        engine.add("shop", "gateway", mapOf("upstream" to "http://localhost:7000"))
        assertContains(dir.resolve("apisix.yaml").readText(), "\"host.docker.internal:7000\": 1")

        // gateway.yml's own upstream wins over the option.
        dir.resolve("gateway.yml").writeText("upstream: http://api.internal:9000\n")
        engine.refresh("shop")
        assertContains(dir.resolve("apisix.yaml").readText(), "\"api.internal:9000\": 1")
    }

    @Test
    fun `the gateway compose entries run apisix and gantry-auth`() {
        engine.create("shop", services = listOf("gateway"))
        val compose = dir.resolve("compose.yml").readText()
        val runtime = home.resolve("runtime")
        val staged = Files.list(runtime).use { it.toList() }.single()

        assertContains(compose, "image: \"apache/apisix:3.19.0-debian\"")
        assertContains(compose, "container_name: \"shop-gateway\"")
        val port =
            engine
                .loadApp("shop")
                .service("gateway")!!
                .ports
                .getValue("main")
        assertTrue(port >= 8090)
        assertContains(compose, "\"127.0.0.1:$port:9080\"")
        assertContains(compose, "\"$dir/config.yaml:/usr/local/apisix/conf/config.yaml")
        assertContains(compose, "\"$dir/apisix.yaml:/usr/local/apisix/conf/apisix.yaml")
        assertContains(compose, "\"$dir/gateway-log:/gantry-log\"")
        assertContains(compose, "gantry-auth:")
        assertContains(compose, "container_name: \"shop-gantry-auth\"")
        assertContains(compose, "image: \"eclipse-temurin:17-jre\"")
        assertContains(compose, "\"$staged:/gantry:ro\"")
        assertContains(compose, "\"$dir:/config:ro\"")
        assertContains(compose, "dev.gantry.auth.AuthServerKt")
        assertContains(compose, "\"--in-container\"")
        assertContains(compose, "- --upstream\n      - \"http://localhost:8080\"")
        assertContains(compose, "- --app\n      - \"shop\"")
        assertContains(compose, "/config/gateway-secrets.json")
        assertEquals(2, Regex("host.docker.internal:host-gateway").findAll(compose).count())
        assertTrue(Files.isDirectory(dir.resolve("gateway-log")))
        assertEquals("http://localhost:$port", engine.env(engine.loadApp("shop")).toMap()["GATEWAY_URL"])
    }

    @Test
    fun `the call log directory is owner-only on macOS`() {
        val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        val app = App("shop", listOf(ServiceState(GATEWAY)))
        val appDir = Files.createDirectories(dir)
        prepareGatewayFiles(app, appDir, ownerOnlyLogs = true)
        val logs = appDir.resolve("gateway-log")
        assertEquals(ownerOnly, Files.getPosixFilePermissions(logs))

        // A directory left wider by an older gantry is tightened on the next render.
        Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("rwxr-xr-x"))
        prepareGatewayFiles(app, appDir, ownerOnlyLogs = true)
        assertEquals(ownerOnly, Files.getPosixFilePermissions(logs))
    }

    @Test
    fun `elsewhere the call log directory is sticky and write-only for others, with the log files pre-created`() {
        val app = App("shop", listOf(ServiceState(GATEWAY)))
        val appDir = Files.createDirectories(dir)
        val logs = Files.createDirectories(appDir.resolve("gateway-log"))
        Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("rwxrwxrwx"))

        prepareGatewayFiles(app, appDir, ownerOnlyLogs = false)

        // 01733: others can create files (APISIX's uid, gantry-auth's root) but not list, rename or delete them.
        assertEquals(0b1_111_011_011, (Files.getAttribute(logs, "unix:mode") as Int) and 0b1_111_111_111)
        listOf("calls.log", "auth.log").forEach { name ->
            val file = logs.resolve(name)
            assertTrue(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), name)
            assertEquals(PosixFilePermissions.fromString("rw-rw-rw-"), Files.getPosixFilePermissions(file))
        }

        // An existing log keeps its content; a symlink planted in its place is replaced by a fresh file.
        Files.writeString(logs.resolve("calls.log"), "kept\n")
        Files.delete(logs.resolve("auth.log"))
        val outside = Files.createTempFile("gantry-outside", ".log")
        Files.createSymbolicLink(logs.resolve("auth.log"), outside)
        prepareGatewayFiles(app, appDir, ownerOnlyLogs = false)
        assertEquals("kept\n", logs.resolve("calls.log").readText())
        assertTrue(Files.isRegularFile(logs.resolve("auth.log"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(outside))
    }

    @Test
    fun `owner-only call logs are the default only on macOS`() {
        assertTrue(ownerOnlyCallLogs("Mac OS X"))
        assertFalse(ownerOnlyCallLogs("Linux"))
        assertFalse(ownerOnlyCallLogs("Windows 11"))
    }

    @Test
    fun `rendered gateway files contain no placeholders`() {
        engine.create("shop", services = listOf("gateway"))
        assertTrue(engine.saveGatewayConfig("shop", withSecrets).isEmpty())
        listOf("config.yaml", "apisix.yaml", "compose.yml").forEach {
            assertFalse("{{" in dir.resolve(it).readText(), "$it has a placeholder left")
        }
    }

    @Test
    fun `apisix yaml is rewritten in place`() {
        engine.create("shop", services = listOf("gateway"))
        val file = dir.resolve("apisix.yaml")
        val inode = Files.getAttribute(file, "unix:ino")

        val problems = engine.saveGatewayConfig("shop", "upstream: http://localhost:9999\n")

        assertEquals(emptyList(), problems)
        assertEquals(inode, Files.getAttribute(file, "unix:ino"))
        assertContains(file.readText(), "host.docker.internal:9999")
        assertEquals("upstream: http://localhost:9999\n", dir.resolve("gateway.yml").readText())
        assertEquals("http://localhost:9999", engine.gatewayConfig("shop").upstream)
    }

    @Test
    fun `invalid config is refused and the old one kept`() {
        engine.create("shop", services = listOf("gateway"))
        val before = dir.resolve("apisix.yaml").readText()

        listOf(
            "routes:\n  - name: r\n    match: {path: /x}\n    auth: nobody\n",
            "upstream: [not, a, url\n",
            "bogus: 1\n",
            "upstream: http://localhost:8080/with/path\n",
        ).forEach { bad ->
            val problems = engine.saveGatewayConfig("shop", bad)
            assertTrue(problems.isNotEmpty(), "refuses $bad")
            assertEquals(DEFAULT_GATEWAY_YML, dir.resolve("gateway.yml").readText())
            assertEquals(before, dir.resolve("apisix.yaml").readText())
        }
        assertContains(engine.saveGatewayConfig("shop", "default:\n  auth: nobody\n").single(), "unknown authorizer 'nobody'")
    }

    @Test
    fun `saving needs the gateway service`() {
        engine.create("shop", services = listOf("valkey"))
        assertFailsWith<GantryException> { engine.saveGatewayConfig("shop", "") }
    }

    @Test
    fun `gateway-secrets json holds only referenced secrets and is owner-only`() {
        engine.create("shop", services = listOf("gateway"))
        engine.secrets.setSecret("shop", "SUPABASE_JWT", "jwt-secret")
        engine.secrets.setSecret("shop", "UNUSED", "nope")
        engine.secrets.setSecret("other", "INTERNAL_API_KEY", "not this app's")

        assertTrue(engine.saveGatewayConfig("shop", withSecrets).isEmpty())

        val file = dir.resolve("gateway-secrets.json")
        val secrets =
            kotlinx.serialization.json.Json
                .decodeFromString<Map<String, String>>(file.readText())
        // INTERNAL_API_KEY is referenced but not set: left out, and reported by gantry-auth when a request needs it.
        assertEquals(mapOf("SUPABASE_JWT" to "jwt-secret"), secrets)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file))
        assertFalse("jwt-secret" in dir.resolve("compose.yml").readText())
        assertFalse("jwt-secret" in dir.resolve("apisix.yaml").readText())

        engine.remove("shop", "gateway")
        assertFalse(Files.exists(file))
    }

    @Test
    fun `runtime is staged once per version`() {
        val staged = GatewayRuntime.stage(home, lib, version = "1.2.3")
        assertTrue(staged.fileName.toString().startsWith("gantry-1.2.3-"))
        assertEquals(home.resolve("runtime"), staged.parent)
        assertEquals("jar bytes", staged.resolve("lib/gantry-0.0.0.jar").readText())
        assertEquals("more bytes", staged.resolve("lib/kaml.jar").readText())

        // Already there: nothing is copied again.
        staged.resolve("lib/kaml.jar").writeText("touched")
        assertEquals(staged, GatewayRuntime.stage(home, lib, version = "1.2.3"))
        assertEquals("touched", staged.resolve("lib/kaml.jar").readText())

        // A new version, or other jars under the same version, get a directory of their own.
        assertNotEquals(staged, GatewayRuntime.stage(home, lib, version = "1.2.4"))
        lib.resolve("gantry-0.0.0.jar").writeText("rebuilt jar")
        val rebuilt = GatewayRuntime.stage(home, lib, version = "1.2.3")
        assertNotEquals(staged, rebuilt)
        assertEquals("rebuilt jar", rebuilt.resolve("lib/gantry-0.0.0.jar").readText())
    }

    @Test
    fun `a missing lib directory is a clear error`() {
        val e = assertFailsWith<GantryException> { GatewayRuntime.stage(home, home.resolve("nowhere"), version = "1") }
        assertContains(e.message!!, "nowhere")
    }

    @Test
    fun `gantry-auth reads the applied config, which changes only when the config is applied`() {
        engine.create("shop", services = listOf("gateway"))
        val applied = dir.resolve("gateway.applied.yml")
        assertEquals(DEFAULT_GATEWAY_YML, applied.readText())
        assertContains(dir.resolve("compose.yml").readText(), "- --config\n      - /config/gateway.applied.yml")

        // A hand edit reaches neither APISIX nor gantry-auth by itself.
        val routesBefore = dir.resolve("apisix.yaml").readText()
        val edited = "routes:\n  - name: admin\n    match: {path: /admin/**}\n    auth: deny\n"
        dir.resolve("gateway.yml").writeText(edited)
        assertEquals(DEFAULT_GATEWAY_YML, applied.readText())
        assertEquals(routesBefore, dir.resolve("apisix.yaml").readText())

        // Applying it (what `gantry gateway reload` does) switches both together.
        assertEquals(emptyList(), engine.saveGatewayConfig("shop", engine.appDir("shop").resolve("gateway.yml").readText()))
        assertEquals(edited, applied.readText())
        assertContains(dir.resolve("apisix.yaml").readText(), "\"id\": \"admin\"")

        // An invalid save changes neither.
        assertTrue(engine.saveGatewayConfig("shop", "default:\n  auth: nobody\n").isNotEmpty())
        assertEquals(edited, applied.readText())

        engine.remove("shop", "gateway")
        assertFalse(Files.exists(applied))
    }

    @Test
    fun `a custom authorizer route goes to gantry-auth until its env secrets are set`() {
        engine.create("shop", services = listOf("gateway"))
        engine.setAuthorizers("shop", listOf(PushedAuthorizer("checker", "/src/checker", image = authorizerImage("shop", "checker"))))
        val yml =
            """
            authorizers:
              mine:
                type: custom
                container: checker
                env:
                  API_KEY: "{{secret:API_KEY}}"
            routes:
              - name: api
                match:
                  path: /api/**
                auth: mine
            """.trimIndent()
        assertEquals(emptyList(), engine.saveGatewayConfig("shop", yml))
        val unset = dir.resolve("apisix.yaml").readText()
        assertContains(unset, "\"uri\": \"http://gantry-auth:9100/authorize?route=api\"")
        assertFalse("auth-checker" in unset)

        // Setting the secret changes the container's env file, but the running container only reads it when it is
        // recreated: a render alone keeps the route on gantry-auth (see GatewayCliTest for the restart).
        engine.secrets.setSecret("shop", "API_KEY", "k-1")
        engine.refresh("shop")
        assertContains(dir.resolve("apisix.yaml").readText(), "\"uri\": \"http://gantry-auth:9100/authorize?route=api\"")
        engine.refresh("shop")
        assertFalse("auth-checker" in dir.resolve("apisix.yaml").readText())

        // Started with the current env file (by `secret set`, `gateway reload` or `gantry start`): back to the container.
        assertTrue(engine.authorizersStartedWith("shop", engine.authorizerEnvFiles("shop")))
        engine.refresh("shop")
        assertContains(dir.resolve("apisix.yaml").readText(), "\"uri\": \"http://auth-checker:8080/\"")
        assertFalse(Files.exists(dir.resolve("authorizers-pending.json")))

        // A change undone before any restart leaves nothing pending: the container still has that env.
        engine.secrets.setSecret("shop", "API_KEY", "k-2")
        engine.refresh("shop")
        assertFalse("auth-checker" in dir.resolve("apisix.yaml").readText())
        engine.secrets.setSecret("shop", "API_KEY", "k-1")
        engine.refresh("shop")
        assertContains(dir.resolve("apisix.yaml").readText(), "\"uri\": \"http://auth-checker:8080/\"")
        assertFalse(Files.exists(dir.resolve("authorizers-pending.json")))
    }

    @Test
    fun `a second save in the same second still moves apisix_yaml's whole-second modification time`() {
        // APISIX re-reads apisix.yaml only when its modification time in whole seconds changes, so a second change
        // written in the second it already read would never be applied.
        engine.create("shop", services = listOf("gateway"))
        val routes = dir.resolve("apisix.yaml")
        repeat(3) { i ->
            // Start early in a second, so the two saves below fall in the same one without the fix.
            while (System.currentTimeMillis() % 1000 > 200) Thread.sleep(5)
            assertEquals(emptyList(), engine.saveGatewayConfig("shop", "routes:\n  - name: a$i\n    match: {path: /a}\n"))
            val first = Files.getLastModifiedTime(routes).toMillis() / 1000
            assertEquals(emptyList(), engine.saveGatewayConfig("shop", "routes:\n  - name: b$i\n    match: {path: /b}\n"))
            val second = Files.getLastModifiedTime(routes).toMillis() / 1000
            assertTrue(second > first, "apisix.yaml changed but stayed in second $first")
            assertContains(routes.readText(), "\"b$i\"")
        }
        // A render that changes nothing leaves the file and its time alone.
        val stamp = Files.getLastModifiedTime(routes)
        engine.refresh("shop")
        assertEquals(stamp, Files.getLastModifiedTime(routes))
    }
}
