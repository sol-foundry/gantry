package dev.gantry.cli

import com.github.ajalt.clikt.testing.test
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.NO_DOCKER
import dev.gantry.gateway.AuthorizerContainers
import dev.gantry.gateway.Authorizers
import dev.gantry.gateway.DEFAULT_GATEWAY_YML
import dev.gantry.gateway.PushedAuthorizer
import dev.gantry.gateway.callLog
import dev.gantry.gateway.saveGatewayConfig
import dev.gantry.share.Exporter
import dev.gantry.share.Importer
import dev.gantry.share.encode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayCliTest {
    private val lib: Path = Files.createTempDirectory("gantry-lib").also { it.resolve("gantry.jar").writeText("jar") }
    private val engine =
        Engine(home = Files.createTempDirectory("gantry-cli-gateway"), dockerCommand = NO_DOCKER, runtimeLib = lib).apply {
            create("shop", services = listOf("gateway"))
        }
    private val docker = FakeContainers()
    private val authorizers = Authorizers(engine, docker)
    private val dir get() = engine.appDir("shop")

    private fun gateway() = GatewayCommand(engine, authorizers)

    private fun authorizer() = AuthorizerCommand(engine, authorizers)

    private fun secret(stdin: String = "") = SecretCommand(engine, authorizers) { stdin }

    private fun source(): Path =
        Files.createTempDirectory("gantry-authorizer-src").also {
            it.resolve("Dockerfile").writeText("FROM scratch\n")
        }

    private fun configFile(text: String): Path = Files.createTempFile("gateway", ".yml").also { it.writeText(text) }

    private val customConfig =
        """
        authorizers:
          mine:
            type: custom
            container: check
            env:
              API_KEY: "{{secret:API_KEY}}"
          other:
            type: custom
            container: spare
        default:
          auth: mine
        """.trimIndent() + "\n"

    // secret

    @Test
    fun `secret set reads the value from stdin and drops one trailing newline`() {
        val result = secret("s3cret\n").test("set shop API_KEY")

        assertEquals(0, result.statusCode, result.output)
        assertEquals("s3cret", engine.secrets.secret("shop", "API_KEY"))
        assertFalse("s3cret" in result.output)
    }

    @Test
    fun `only one trailing newline is dropped`() {
        secret("line1\nline2\n\n").test("set shop CERT")
        assertEquals("line1\nline2\n", engine.secrets.secret("shop", "CERT"))
        secret("crlf\r\n").test("set shop WIN")
        assertEquals("crlf", engine.secrets.secret("shop", "WIN"))
    }

    @Test
    fun `secret set reaches the rendered files`() {
        assertEquals(emptyList(), engine.saveGatewayConfig("shop", customConfig))
        secret("s3cret\n").test("set shop API_KEY")

        assertContains(dir.resolve("gateway-secrets.json").readText(), "s3cret")
    }

    @Test
    fun `secret set refuses bad names and an empty value`() {
        listOf("a}b", "has space").forEach { bad ->
            assertFailsWith<GantryException>(bad) { secret("x\n").test(listOf("set", "shop", bad)) }
        }
        assertFailsWith<GantryException> { secret("").test("set shop API_KEY") }
        assertNull(engine.secrets.secret("shop", "API_KEY"))
    }

    @Test
    fun `secret set recreates only the running authorizers whose env changed`() {
        authorizers.push("shop", "check", source()) {}
        authorizers.push("shop", "spare", source()) {}
        engine.saveGatewayConfig("shop", customConfig)
        docker.calls.clear()
        docker.running = setOf("auth-check", "auth-spare", "gateway")

        secret("v1\n").test("set shop API_KEY")
        assertEquals(listOf("recreate shop auth-check"), docker.calls)

        // The same value again changes nothing.
        docker.calls.clear()
        secret("v1\n").test("set shop API_KEY")
        assertEquals(emptyList(), docker.calls)

        // A stopped authorizer is left for `gantry start`.
        docker.running = setOf("gateway")
        secret("v2\n").test("set shop API_KEY")
        assertEquals(emptyList(), docker.calls)
        assertContains(dir.resolve("auth-check.env").readText(), "API_KEY=v2")
    }

    private val routes get() = dir.resolve("apisix.yaml").readText()

    private val toContainer = "\"uri\": \"http://auth-check:8080/\""

    private val toGantryAuth = "\"uri\": \"http://gantry-auth:9100/authorize?route=default\""

    /** Pushes `check` (running) with [customConfig], whose default route uses it. */
    private fun runningCheck() {
        authorizers.push("shop", "check", source()) {}
        assertEquals(emptyList(), engine.saveGatewayConfig("shop", customConfig))
        docker.calls.clear()
        docker.running = setOf("auth-check", "gateway")
    }

    @Test
    fun `secret set points the route at the container only after it restarted with the secret`() {
        runningCheck()
        assertContains(routes, toGantryAuth)
        docker.onRecreate = {
            // The moment compose starts the new container: APISIX must not send requests to the old one.
            assertFalse(toContainer in routes, routes)
            assertContains(routes, toGantryAuth)
            assertContains(dir.resolve("auth-check.env").readText(), "API_KEY=v1")
        }

        assertEquals(0, secret("v1\n").test("set shop API_KEY").statusCode)
        assertEquals(listOf("recreate shop auth-check"), docker.calls)
        assertContains(routes, toContainer)

        // A new value: the running container holds the old one until it is recreated.
        docker.calls.clear()
        docker.onRecreate = {
            assertFalse(toContainer in routes, routes)
            assertContains(dir.resolve("auth-check.env").readText(), "API_KEY=v2")
        }
        assertEquals(0, secret("v2\n").test("set shop API_KEY").statusCode)
        assertEquals(listOf("recreate shop auth-check"), docker.calls)
        assertContains(routes, toContainer)
    }

    @Test
    fun `a failed restart leaves the route on gantry-auth, across later renders, until a restart works`() {
        runningCheck()
        docker.exit = 1

        val failed = runCatching { secret("v1\n").test("set shop API_KEY") }
        assertTrue(failed.isFailure || failed.getOrThrow().statusCode != 0)
        assertEquals(listOf("recreate shop auth-check"), docker.calls)
        assertFalse(toContainer in routes, routes)

        // Any later render (another secret, `gantry env`, the dashboard) keeps it there: the container is still stale.
        engine.secrets.setSecret("shop", "UNRELATED", "x")
        engine.refresh("shop")
        assertFalse(toContainer in routes, routes)
        val again = runCatching { secret("x2\n").test("set shop UNRELATED") }
        assertTrue(again.isFailure || again.getOrThrow().statusCode != 0, "the stale container is retried")
        assertFalse(toContainer in routes, routes)

        docker.exit = 0
        docker.calls.clear()
        assertEquals(0, gateway().test("reload shop").statusCode)
        assertEquals(listOf("recreate shop auth-check"), docker.calls)
        assertContains(routes, toContainer)
    }

    @Test
    fun `a failed docker ps keeps the authorizer pending and its route on gantry-auth`() {
        runningCheck()
        docker.psFails = true

        val failed = assertFailsWith<GantryException> { secret("v1\n").test("set shop API_KEY") }
        assertContains(failed.message!!, "could not ask Docker")
        assertEquals(emptyList(), docker.calls)
        assertFalse(toContainer in routes, routes)
        engine.refresh("shop")
        assertFalse(toContainer in routes, routes)

        docker.psFails = false
        assertEquals(0, gateway().test("reload shop").statusCode)
        assertEquals(listOf("recreate shop auth-check"), docker.calls)
        assertContains(routes, toContainer)
    }

    @Test
    fun `a stopped authorizer container is removed before its route goes back to it`() {
        runningCheck()
        docker.running = setOf("gateway")
        docker.stopped = setOf("auth-check")
        // A later `docker start` would run the old env; removed, the next `gantry start` creates it from the new file.
        docker.onRemove = { assertFalse(toContainer in routes, routes) }

        assertEquals(0, secret("v1\n").test("set shop API_KEY").statusCode)
        assertEquals(listOf("remove shop-auth-check"), docker.calls)
        assertContains(routes, toContainer)
    }

    @Test
    fun `a stopped authorizer container that cannot be removed stays pending`() {
        runningCheck()
        docker.running = setOf("gateway")
        docker.stopped = setOf("auth-check")
        docker.removeFails = true

        val failed = assertFailsWith<GantryException> { secret("v1\n").test("set shop API_KEY") }
        assertContains(failed.message!!, "auth-check")
        assertFalse(toContainer in routes, routes)
        engine.refresh("shop")
        assertFalse(toContainer in routes, routes)
    }

    @Test
    fun `an authorizer without any container is not pending`() {
        runningCheck()
        docker.running = setOf("gateway")

        assertEquals(0, secret("v1\n").test("set shop API_KEY").statusCode)
        assertEquals(emptyList(), docker.calls)
        assertContains(routes, toContainer)
    }

    @Test
    fun `a failed restart is the error reported, even when the re-render after it fails too`() {
        authorizers.push("shop", "check", source()) {}
        authorizers.push("shop", "spare", source()) {}
        val both = customConfig.replace("    container: spare\n", "    container: spare\n    env:\n      KEY: \"{{secret:API_KEY}}\"\n")
        assertEquals(emptyList(), engine.saveGatewayConfig("shop", both))
        docker.calls.clear()
        docker.running = setOf("auth-check", "auth-spare", "gateway")
        docker.exitFor = { service ->
            if (service == "auth-spare") {
                // The re-render that follows the restarts now fails as well.
                dir.resolve("gateway.yml").writeText("default:\n  auth: nobody\n")
                1
            } else {
                0
            }
        }

        val failed = assertFailsWith<GantryException> { secret("v1\n").test("set shop API_KEY") }
        assertContains(failed.message!!, "could not restart auth-spare")
        assertContains(failed.suppressed.single().message!!, "gateway.yml is invalid")
    }

    @Test
    fun `secret remove takes the route off the container before it restarts without the secret`() {
        runningCheck()
        secret("v1\n").test("set shop API_KEY")
        assertContains(routes, toContainer)
        docker.calls.clear()
        docker.onRecreate = {
            assertFalse(toContainer in routes, routes)
            assertFalse("API_KEY" in dir.resolve("auth-check.env").readText())
        }

        assertEquals(0, secret().test("remove shop API_KEY").statusCode)
        assertEquals(listOf("recreate shop auth-check"), docker.calls)
        assertFalse(toContainer in routes, routes)
    }

    @Test
    fun `gateway config --file switches the route back only after the restart`() {
        runningCheck()
        secret("v1\n").test("set shop API_KEY")
        docker.calls.clear()
        val changed = customConfig.replace("      API_KEY:", "      MODE: strict\n      API_KEY:")
        docker.onRecreate = {
            assertFalse(toContainer in routes, routes)
            assertContains(dir.resolve("auth-check.env").readText(), "MODE=strict")
        }

        assertEquals(0, gateway().test(listOf("config", "shop", "--file", configFile(changed).toString())).statusCode)
        assertEquals(listOf("recreate shop auth-check"), docker.calls)
        assertContains(routes, toContainer)
    }

    @Test
    fun `a failed refresh after secret set says the secret was saved`() {
        dir.resolve("gateway.yml").writeText("default:\n  auth: nobody\n")

        val result = secret("v1\n").test("set shop API_KEY")

        assertEquals(1, result.statusCode)
        assertContains(result.stderr, "saved secret API_KEY of 'shop', but the app could not be refreshed: gateway.yml is invalid")
        assertEquals("v1", engine.secrets.secret("shop", "API_KEY"))
    }

    @Test
    fun `secret list shows names, never values, and the ones still to set`() {
        engine.saveGatewayConfig("shop", customConfig)
        engine.secrets.setSecret("shop", "OTHER", "hidden-value")

        val result = secret().test("list shop")

        assertEquals(0, result.statusCode, result.output)
        assertContains(result.output, "OTHER")
        assertContains(result.output, "API_KEY")
        assertContains(result.output, "not set")
        assertFalse("hidden-value" in result.output)
    }

    @Test
    fun `secret remove forgets one secret`() {
        engine.secrets.setSecret("shop", "A", "1")
        engine.secrets.setSecret("shop", "B", "2")

        assertEquals(0, secret().test("remove shop A").statusCode)

        assertNull(engine.secrets.secret("shop", "A"))
        assertEquals("2", engine.secrets.secret("shop", "B"))
        assertFailsWith<GantryException> { secret().test("remove shop A") }
    }

    // gateway config

    @Test
    fun `gateway config prints the file`() {
        val result = gateway().test("config shop")
        assertEquals(DEFAULT_GATEWAY_YML, result.stdout)
    }

    @Test
    fun `gateway config --file validates and saves`() {
        val text = "upstream: http://localhost:9999\n"
        val result = gateway().test(listOf("config", "shop", "--file", configFile(text).toString()))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(text, dir.resolve("gateway.yml").readText())
    }

    @Test
    fun `an invalid file is not saved and its problems are printed`() {
        val result = gateway().test(listOf("config", "shop", "--file", configFile("default:\n  auth: nobody\n").toString()))

        assertNotEquals(0, result.statusCode)
        assertContains(result.stderr, "unknown authorizer 'nobody'")
        assertEquals(DEFAULT_GATEWAY_YML, dir.resolve("gateway.yml").readText())
    }

    @Test
    fun `saving a config recreates running authorizers whose env changed`() {
        authorizers.push("shop", "check", source()) {}
        authorizers.push("shop", "spare", source()) {}
        engine.secrets.setSecret("shop", "API_KEY", "k")
        docker.calls.clear()
        docker.running = setOf("auth-check", "auth-spare")

        gateway().test(listOf("config", "shop", "--file", configFile(customConfig).toString()))

        assertEquals(listOf("recreate shop auth-check"), docker.calls)
    }

    @Test
    fun `gateway reload applies a hand-edited gateway yml`() {
        val edited = "routes:\n  - name: admin\n    match: {path: /admin/**}\n    auth: deny\n"
        dir.resolve("gateway.yml").writeText(edited)
        assertEquals(DEFAULT_GATEWAY_YML, dir.resolve("gateway.applied.yml").readText())

        val result = gateway().test("reload shop")

        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "applied gateway.yml of 'shop'")
        assertEquals(edited, dir.resolve("gateway.applied.yml").readText())
        assertContains(dir.resolve("apisix.yaml").readText(), "\"id\": \"admin\"")
    }

    @Test
    fun `gateway reload of an invalid gateway yml changes nothing and lists the problems`() {
        val routes = dir.resolve("apisix.yaml").readText()
        dir.resolve("gateway.yml").writeText("default:\n  auth: nobody\n")

        val result = gateway().test("reload shop")

        assertNotEquals(0, result.statusCode)
        assertContains(result.stderr, "unknown authorizer 'nobody'")
        assertEquals(DEFAULT_GATEWAY_YML, dir.resolve("gateway.applied.yml").readText())
        assertEquals(routes, dir.resolve("apisix.yaml").readText())
        // The hand edit itself is kept, to be fixed.
        assertEquals("default:\n  auth: nobody\n", dir.resolve("gateway.yml").readText())
    }

    @Test
    fun `a broken hand-edited gateway yml says how to recover`() {
        dir.resolve("gateway.yml").writeText("default:\n  auth: nobody\n")

        val e = assertFailsWith<GantryException> { engine.refresh("shop") }

        assertContains(e.message!!, "gateway.yml is invalid")
        assertContains(e.message!!, "unknown authorizer 'nobody'")
        assertContains(e.message!!, "gantry gateway config shop --file ${dir.resolve("gateway.yml")}")
    }

    // gateway calls

    private fun call(
        id: String,
        uri: String,
        status: Int,
        start: Long,
    ) = """{"request":{"uri":"$uri","method":"GET","headers":{"x-request-id":"$id"}},"start_time":$start,"latency":3.6,""" +
        """"response":{"status":$status,"headers":{}},"route_id":"default"}"""

    private fun writeCalls(vararg lines: String) {
        val log = Files.createDirectories(dir.resolve("gateway-log"))
        Files.writeString(log.resolve("calls.log"), lines.joinToString("\n", postfix = "\n"))
        Files.writeString(log.resolve("auth.log"), """{"requestId":"c2","decision":"allow","context":{"userId":"u-7"}}""" + "\n")
    }

    @Test
    fun `gateway calls prints a table, oldest first, with filters`() {
        writeCalls(
            call("c1", "/api/a", 200, 1793814923000),
            call("c2", "/api/b?x=1", 404, 1793814924000),
            call("c3", "/other", 500, 1793814925000),
        )

        val all =
            gateway()
                .test("calls shop")
                .stdout
                .lines()
                .filter { it.isNotBlank() }
        assertEquals(4, all.size, all.toString())
        listOf("TIME", "METHOD", "PATH", "STATUS", "MS", "USER").forEach { assertContains(all[0], it) }
        assertContains(all[1], "/api/a")
        assertContains(all[3], "/other")
        assertContains(all[2], "u-7")

        val filtered = gateway().test("calls shop --status 4xx --path /api/**").stdout
        assertContains(filtered, "/api/b?x=1")
        assertFalse("/api/a" in filtered)
        assertFalse("/other" in filtered)

        val limited = gateway().test("calls shop --limit 1").stdout
        assertContains(limited, "/other")
        assertFalse("/api/a" in limited)
    }

    @Test
    fun `following prints only calls not seen before`() {
        writeCalls(call("c1", "/a", 200, 1793814923000))
        val follower = CallFollower(engine.callLog("shop"), status = null, path = null)
        assertEquals(listOf("c1"), follower.poll().map { it.id })
        assertEquals(emptyList(), follower.poll())

        writeCalls(call("c1", "/a", 200, 1793814923000), call("c2", "/b", 200, 1793814924000), call("c3", "/c", 200, 1793814925000))
        assertEquals(listOf("c2", "c3"), follower.poll().map { it.id })
    }

    @Test
    fun `gateway clear empties the call log`() {
        writeCalls(call("c1", "/a", 200, 1793814923000))
        assertContains(gateway().test("clear shop").stdout, "Cleared")
        assertEquals(
            1,
            gateway()
                .test("calls shop")
                .stdout
                .lines()
                .count { it.isNotBlank() },
        )
    }

    @Test
    fun `an app without a gateway is told so`() {
        engine.create("plain", services = listOf("valkey"))
        val e = assertFailsWith<GantryException> { gateway().test("calls plain") }
        assertContains(e.message!!, "no gateway")
    }

    // authorizer

    @Test
    fun `authorizer init writes the template into --dir`() {
        val target = Files.createTempDirectory("gantry-init").resolve("check")
        val result = authorizer().test(listOf("init", "check", "--lang", "node", "--dir", target.toString()))

        assertEquals(0, result.statusCode, result.output)
        assertTrue(Files.isRegularFile(target.resolve("server.js")))
        assertContains(result.output, "gantry authorizer push <app> check $target")
    }

    @Test
    fun `authorizer init needs a known language`() {
        val e =
            assertFailsWith<GantryException> {
                authorizer().test(listOf("init", "check", "--lang", "cobol", "--dir", Files.createTempDirectory("x").toString()))
            }
        assertContains(e.message!!, "go, node, python")
    }

    @Test
    fun `authorizer push streams the build and list shows it`() {
        val src = source()
        val pushed = authorizer().test(listOf("push", "shop", "check", src.toString()))

        assertEquals(0, pushed.statusCode, pushed.output)
        assertContains(pushed.output, "building gantry-shop-auth-check:latest")

        val listed = authorizer().test("list shop").stdout
        assertContains(listed, "check")
        assertContains(listed, "8080")
        assertContains(listed, src.toAbsolutePath().toString())
    }

    @Test
    fun `authorizer remove warns when gateway yml still uses it`() {
        authorizers.push("shop", "check", source()) {}
        engine.saveGatewayConfig("shop", customConfig)

        val result = authorizer().test("remove shop check")

        assertEquals(0, result.statusCode, result.output)
        assertEquals(emptyList(), authorizers.list("shop"))
        assertContains(result.stderr, "warning:")
        assertContains(result.stderr, "mine")
    }

    @Test
    fun `authorizer list of an app without any says so`() {
        assertContains(authorizer().test("list shop").stdout, "no authorizers")
    }

    // import

    @Test
    fun `import prints the secrets to set and the authorizers to push`() {
        engine.saveGatewayConfig("shop", customConfig)
        engine.setAuthorizers("shop", listOf(PushedAuthorizer("check", "/src/check", image = "gantry-shop-auth-check:latest")))
        val exported = Files.createTempFile("shop", ".json")
        exported.writeText(Exporter(engine).export("shop").encode())

        val result = Import(engine, Importer(engine) { false }).test(listOf(exported.toString(), "--as", "copy", "--yes"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(customConfig, engine.appDir("copy").resolve("gateway.yml").readText())
        assertContains(result.output, "gantry secret set copy API_KEY")
        assertContains(result.output, "gantry authorizer push copy check /src/check")
        assertEquals(emptyList(), engine.loadApp("copy").authorizers)
    }

    private class FakeContainers : AuthorizerContainers {
        val calls = mutableListOf<String>()
        var running = emptySet<String>()
        var exit = 0
        var onRecreate: () -> Unit = {}
        var exitFor: (String) -> Int = { exit }

        /** Containers that exist but are stopped. */
        var stopped = emptySet<String>()

        /** `docker ps` fails: like the real runner, the running list then comes back empty. */
        var psFails = false
        var removeFails = false
        var onRemove: () -> Unit = {}

        override fun build(
            image: String,
            dir: Path,
            onLine: (String) -> Unit,
        ): Int {
            calls += "build $image"
            onLine("building $image")
            return 0
        }

        override fun appRunning(app: String) = running.isNotEmpty()

        override fun runningServices(app: String) = if (psFails) emptySet() else running

        override fun serviceStates(app: String) =
            if (psFails) null else running.associateWith { "running" } + stopped.associateWith { "exited" }

        override fun recreate(
            app: String,
            service: String,
            onLine: (String) -> Unit,
        ): Int {
            calls += "recreate $app $service"
            onRecreate()
            return exitFor(service)
        }

        override fun remove(container: String) {
            calls += "remove $container"
            onRemove()
            if (!removeFails) stopped = stopped - container.removePrefix("shop-")
        }
    }
}
