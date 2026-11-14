package dev.gantry.gateway

import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.NO_DOCKER
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthorizersTest {
    private val home: Path = Files.createTempDirectory("gantry-authorizers")
    private val lib: Path = Files.createTempDirectory("gantry-lib").also { it.resolve("gantry.jar").writeText("jar") }
    private val engine = Engine(home = home, dockerCommand = NO_DOCKER, gatewayLib = lib)
    private val docker = FakeDocker()
    private val authorizers = Authorizers(engine, docker)
    private val appDir get() = engine.appDir("shop")
    private val compose get() = appDir.resolve("compose.yml").readText()

    /** A directory with a Dockerfile, as `init` leaves it. */
    private fun source(): Path {
        val dir = Files.createTempDirectory("gantry-authorizer-src")
        dir.resolve("Dockerfile").writeText("FROM scratch\n")
        return dir
    }

    @Test
    fun `init writes the template for each language`() {
        val servers = mapOf("node" to "server.js", "python" to "server.py", "go" to "main.go")
        servers.forEach { (lang, server) ->
            val target = Files.createTempDirectory("gantry-init").resolve(lang)
            authorizers.init("check", lang, target)
            listOf("Dockerfile", server, "README.md").forEach { assertTrue(Files.isRegularFile(target.resolve(it)), "$lang: $it") }
            val code = target.resolve(server).readText()
            listOf("X-Forwarded-Method", "X-Forwarded-Uri", "PORT", "dev-token", "x-user-id", "unauthorized").forEach {
                assertContains(code, it, ignoreCase = true, message = "$lang server mentions $it")
            }
            assertContains(target.resolve("README.md").readText(), "check")
            assertFalse("__NAME__" in target.resolve("README.md").readText())
        }
    }

    @Test
    fun `init refuses a non-empty directory`() {
        val target = Files.createTempDirectory("gantry-init")
        target.resolve("keep.txt").writeText("mine")
        val e = assertFailsWith<GantryException> { authorizers.init("check", "node", target) }
        assertContains(e.message!!, "not empty")
        assertEquals(listOf("keep.txt"), Files.list(target).use { s -> s.map { it.fileName.toString() }.toList() })

        // An empty directory is fine.
        val empty = Files.createTempDirectory("gantry-init")
        authorizers.init("check", "python", empty)
        assertTrue(Files.exists(empty.resolve("Dockerfile")))
    }

    @Test
    fun `unknown language lists the supported ones`() {
        val target = Files.createTempDirectory("gantry-init").resolve("x")
        val e = assertFailsWith<GantryException> { authorizers.init("check", "cobol", target) }
        assertContains(e.message!!, "cobol")
        assertContains(e.message!!, "go, node, python")
    }

    @Test
    fun `authorizer names are lowercase and short`() {
        engine.create("shop", services = listOf("gateway"))
        listOf("Check", "1check", "check_me", "a".repeat(31), "", "-x", "check me").forEach { bad ->
            val e = assertFailsWith<GantryException>(bad) { authorizers.init(bad, "node", Files.createTempDirectory("gantry-init")) }
            assertContains(e.message!!, "authorizer name")
            assertFailsWith<GantryException>(bad) { authorizers.push("shop", bad, source()) {} }
        }
        assertTrue(docker.calls.isEmpty())
        authorizers.init("a" + "b".repeat(29), "node", Files.createTempDirectory("gantry-init").resolve("ok"))
    }

    @Test
    fun `push builds and records the authorizer`() {
        engine.create("shop", services = listOf("gateway"))
        val dir = source()
        val lines = mutableListOf<String>()

        authorizers.push("shop", "check", dir) { lines += it }

        assertEquals(listOf("build gantry-shop-auth-check:latest ${dir.toAbsolutePath()}"), docker.calls)
        assertEquals(listOf("building gantry-shop-auth-check:latest"), lines)
        val pushed = authorizers.list("shop").single()
        assertEquals(PushedAuthorizer("check", dir.toAbsolutePath().toString(), 8080, "gantry-shop-auth-check:latest"), pushed)
        assertContains(appDir.resolve("app.json").readText(), "gantry-shop-auth-check:latest")
        assertContains(compose, "auth-check:")
    }

    @Test
    fun `push recreates only its own service when the app is running`() {
        engine.create("shop", services = listOf("gateway"))
        docker.running = true
        authorizers.push("shop", "check", source()) {}
        assertEquals("recreate shop auth-check", docker.calls.last())
        assertEquals(2, docker.calls.size)
    }

    @Test
    fun `pushing again keeps one record`() {
        engine.create("shop", services = listOf("gateway"))
        authorizers.push("shop", "check", source()) {}
        val second = source()
        authorizers.push("shop", "check", second) {}
        assertEquals(listOf(second.toAbsolutePath().toString()), authorizers.list("shop").map { it.dir })
    }

    @Test
    fun `a dir inside a git repository is stored relative to it`() {
        engine.create("shop", services = listOf("gateway"))
        val repo = Files.createDirectories(Files.createTempDirectory("gantry-repos").resolve("my-api"))
        Files.createDirectories(repo.resolve(".git"))
        val dir = Files.createDirectories(repo.resolve("auth/check"))
        dir.resolve("Dockerfile").writeText("FROM scratch\n")

        authorizers.push("shop", "check", dir) {}

        assertEquals("my-api/auth/check", authorizers.list("shop").single().dir)
    }

    @Test
    fun `push needs a gateway, a Dockerfile and a successful build`() {
        engine.create("shop", services = listOf("valkey"))
        assertContains(assertFailsWith<GantryException> { authorizers.push("shop", "check", source()) {} }.message!!, "no gateway")

        engine.add("shop", "gateway")
        val empty = Files.createTempDirectory("gantry-authorizer-src")
        assertContains(assertFailsWith<GantryException> { authorizers.push("shop", "check", empty) {} }.message!!, "Dockerfile")
        assertTrue(docker.calls.isEmpty())

        docker.buildExit = 1
        assertContains(assertFailsWith<GantryException> { authorizers.push("shop", "check", source()) {} }.message!!, "build")
        assertTrue(authorizers.list("shop").isEmpty())
        assertFalse("auth-check" in compose)
    }

    @Test
    fun `compose has one service per pushed authorizer`() {
        engine.create("shop", services = listOf("gateway"))
        authorizers.push("shop", "alpha", source()) {}
        authorizers.push("shop", "beta", source()) {}

        val text = compose
        listOf("alpha", "beta").forEach { name ->
            assertContains(text, "  auth-$name:\n    image: \"gantry-shop-auth-$name:latest\"\n")
            assertContains(text, "container_name: \"shop-auth-$name\"\n    pull_policy: never\n")
            assertContains(text, "env_file:\n      - \"$appDir/auth-$name.env\"")
        }
        assertEquals(2, Regex("expose:\n      - \"8080\"").findAll(text).count())
        // apisix and gantry-auth, plus one per authorizer.
        assertEquals(4, Regex("host.docker.internal:host-gateway").findAll(text).count())
        assertEquals(4, Regex("restart: unless-stopped").findAll(text).count())
        assertFalse("{{" in text)
    }

    @Test
    fun `routes call the pushed authorizer on its port`() {
        engine.create("shop", services = listOf("gateway"))
        authorizers.push("shop", "check", source()) {}
        engine.setAuthorizers("shop", authorizers.list("shop").map { it.copy(port = 9001) })
        val config =
            """
            authorizers:
              mine:
                type: custom
                container: check
            default:
              auth: mine
            """.trimIndent()
        assertEquals(emptyList(), engine.saveGatewayConfig("shop", config))
        // The new PORT reaches the container when it is restarted; only then do the routes go to it.
        assertFalse("auth-check" in appDir.resolve("apisix.yaml").readText())
        assertTrue(engine.authorizersStartedWith("shop", engine.authorizerEnvFiles("shop")))
        engine.refresh("shop")

        assertContains(appDir.resolve("apisix.yaml").readText(), "\"http://auth-check:9001/\"")
        assertContains(compose, "expose:\n      - \"9001\"")
        assertContains(appDir.resolve("auth-check.env").readText(), "PORT=9001\n")
    }

    @Test
    fun `env values go to an owner-only env file, never into compose`() {
        engine.create("shop", services = listOf("gateway"))
        engine.secrets.setSecret("shop", "API_KEY", "s3cret-value")
        authorizers.push("shop", "check", source()) {}
        val config =
            """
            authorizers:
              mine:
                type: custom
                container: check
                env:
                  API_KEY: "{{secret:API_KEY}}"
                  BEARER: "Bearer {{secret:API_KEY}}"
                  MODE: strict
                  NOT_SET: "{{secret:MISSING}}"
                  PORT: "1"
              other:
                type: custom
                container: elsewhere
                env:
                  OTHER: x
            default:
              auth: mine
            """.trimIndent()
        assertEquals(emptyList(), engine.saveGatewayConfig("shop", config))

        val file = appDir.resolve("auth-check.env")
        val lines = file.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(listOf("API_KEY=s3cret-value", "BEARER='Bearer s3cret-value'", "MODE=strict", "PORT=8080"), lines)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file))
        assertFalse("s3cret-value" in compose)
        assertFalse("strict" in compose)
        assertFalse("s3cret-value" in appDir.resolve("apisix.yaml").readText())
        assertFalse(Files.exists(appDir.resolve("auth-elsewhere.env")))
    }

    @Test
    fun `remove drops its service`() {
        engine.create("shop", services = listOf("gateway"))
        authorizers.push("shop", "alpha", source()) {}
        authorizers.push("shop", "beta", source()) {}
        assertTrue(Files.exists(appDir.resolve("auth-alpha.env")))

        authorizers.remove("shop", "alpha")

        assertEquals(listOf("beta"), authorizers.list("shop").map { it.name })
        assertFalse("auth-alpha" in compose)
        assertContains(compose, "auth-beta:")
        assertFalse(Files.exists(appDir.resolve("auth-alpha.env")))
        assertTrue(Files.exists(appDir.resolve("auth-beta.env")))
        assertEquals("remove shop-auth-alpha", docker.calls.last())
        assertContains(assertFailsWith<GantryException> { authorizers.remove("shop", "alpha") }.message!!, "alpha")
    }

    @Test
    fun `the env file of an app called auth-something is not taken for an authorizer's`() {
        engine.create("auth-x", services = listOf("gateway"))
        assertTrue(Files.exists(engine.appDir("auth-x").resolve("auth-x.env")))
        assertContains(assertFailsWith<GantryException> { authorizers.push("auth-x", "x", source()) {} }.message!!, "cannot be called")
        authorizers.push("auth-x", "y", source()) {}
        authorizers.remove("auth-x", "y")
        assertTrue(Files.exists(engine.appDir("auth-x").resolve("auth-x.env")))
        assertFalse(Files.exists(engine.appDir("auth-x").resolve("auth-y.env")))
    }

    @Test
    fun `an app json without authorizers still loads`() {
        engine.create("shop", services = listOf("gateway"))
        val file = appDir.resolve("app.json")
        assertFalse("authorizers" in file.readText())
        file.writeText("""{"name": "shop", "services": [{"id": "gateway"}]}""")
        assertEquals(emptyList(), engine.loadApp("shop").authorizers)
        assertEquals(emptyList(), authorizers.list("shop"))
    }

    @Test
    fun `replacing an app keeps its pushed authorizers`() {
        engine.create("shop", services = listOf("gateway"))
        authorizers.push("shop", "check", source()) {}
        engine.replaceApp(engine.loadApp("shop").copy(authorizers = emptyList()))
        assertEquals(listOf("check"), authorizers.list("shop").map { it.name })
    }

    @Test
    fun `gateway yml env names and container names are checked`() {
        val config =
            parseGatewayConfig(
                """
                authorizers:
                  a:
                    type: custom
                    container: Bad_Name
                    env:
                      "not a name": x
                """.trimIndent(),
            )
        val problems = config.validate()
        assertTrue(problems.any { "container" in it && "Bad_Name" in it }, problems.toString())
        assertTrue(problems.any { "not a name" in it }, problems.toString())
    }

    private class FakeDocker : AuthorizerContainers {
        val calls = mutableListOf<String>()
        var running = false
        var buildExit = 0

        override fun build(
            image: String,
            dir: Path,
            onLine: (String) -> Unit,
        ): Int {
            calls += "build $image $dir"
            onLine("building $image")
            return buildExit
        }

        override fun appRunning(app: String): Boolean = running

        override fun runningServices(app: String): Set<String> = if (running) setOf("gateway") else emptySet()

        override fun serviceStates(app: String): Map<String, String> = runningServices(app).associateWith { "running" }

        override fun recreate(
            app: String,
            service: String,
            onLine: (String) -> Unit,
        ): Int {
            calls += "recreate $app $service"
            return 0
        }

        override fun remove(container: String) {
            calls += "remove $container"
        }
    }
}
