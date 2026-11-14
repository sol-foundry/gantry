package dev.gantry.engine

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import dev.gantry.db.DatabaseTarget
import dev.gantry.db.Dialect
import dev.gantry.db.copyScript
import dev.gantry.db.parseTables
import dev.gantry.db.tableCopyScript
import dev.gantry.mcp.connectionDetails
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stand-in for gantry's installed `lib/`, so rendering a database's proxy never needs an installed build. */
val TEST_RUNTIME_LIB: Path by lazy {
    Files.createTempDirectory("gantry-lib").also { it.resolve("gantry-0.0.0.jar").writeText("jar bytes") }
}

private fun plain(node: YamlNode): Any =
    when (node) {
        is YamlMap -> node.entries.entries.associate { (key, value) -> key.content to plain(value) }
        is YamlList -> node.items.map(::plain)
        is YamlScalar -> node.content
        else -> node.toString()
    }

@Suppress("UNCHECKED_CAST")
internal fun composeServices(compose: String): Map<String, Map<String, Any>> =
    (plain(Yaml.default.parseToYamlNode(compose)) as Map<String, Any>)["services"] as Map<String, Map<String, Any>>

class QueryLogServiceTest {
    private val home: Path = Files.createTempDirectory("gantry-dblog")
    private val engine = Engine(home = home, dockerCommand = NO_DOCKER, runtimeLib = TEST_RUNTIME_LIB)
    private val dir get() = engine.appDir("shop")

    private fun services() = composeServices(dir.resolve("compose.yml").readText())

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.list(key: String) = (this[key] as List<Any>?).orEmpty().map { it.toString() }

    private fun ports() = engine.loadApp("shop").service("postgres")!!.ports

    @Test
    fun `with the query log on, main is the proxy and direct the database`() {
        engine.create("shop", services = listOf("postgres", "mysql"))
        val services = services()
        listOf("postgres" to 5432, "mysql" to 3306).forEach { (id, port) ->
            val state = engine.loadApp("shop").service(id)!!
            val proxy = services.getValue(id)
            val db = services.getValue("$id-db")
            assertEquals("shop-$id", proxy["container_name"])
            assertEquals("shop-$id-db", db["container_name"])
            assertTrue(proxy["image"].toString().startsWith("eclipse-temurin:"), proxy.toString())
            assertTrue(db["image"].toString().startsWith("$id:"), db.toString())
            assertEquals(listOf("127.0.0.1:${state.ports.getValue("main")}:$port"), proxy.list("ports"))
            assertEquals(listOf("127.0.0.1:${state.ports.getValue("direct")}:$port"), db.list("ports"))
            assertNotEquals(state.ports.getValue("main"), state.ports.getValue("direct"))

            val command = proxy.list("command")

            fun arg(flag: String) = command[command.indexOf(flag) + 1]
            assertContains(command, "dev.gantry.dblog.QueryLogServerKt")
            assertTrue(command.any { it.startsWith("-Xmx") }, command.toString())
            assertContains(command, "-XX:+ExitOnOutOfMemoryError")
            assertEquals("unless-stopped", proxy["restart"])
            assertEquals(id, arg("--dialect"))
            assertEquals("$port", arg("--listen"))
            assertEquals("$id-db:$port", arg("--upstream"))
            assertEquals("/db-log/$id.jsonl", arg("--log"))
            assertEquals("/tls/shop.p12", arg("--tls"))
            assertEquals("/tls/shop.password", arg("--tls-password-file"))
            assertEquals("1000", arg("--max-rows"))
            assertEquals("1048576", arg("--max-bytes"))
            assertEquals("209715200", arg("--max-size"))
            assertEquals(id, arg("--service"))
            val health = arg("--health-port")
            assertNotEquals("$port", health)
            assertContains(((proxy["healthcheck"] as Map<*, *>)["test"] as List<*>).joinToString(" "), "/dev/tcp/127.0.0.1/$health")

            val volumes = proxy.list("volumes")
            assertTrue(volumes.any { it.endsWith(":/gantry:ro") && it.startsWith(home.resolve("runtime").toString()) }, volumes.toString())
            assertContains(volumes, "${dir.toAbsolutePath()}/db-log:/db-log")
            assertContains(volumes, "${dir.toAbsolutePath()}/tls:/tls:ro")
            assertContains(db.list("volumes"), "$id-data:${if (id == "postgres") "/var/lib/postgresql/data" else "/var/lib/mysql"}")
            assertEquals("service_healthy", ((proxy["depends_on"] as Map<*, *>)["$id-db"] as Map<*, *>)["condition"])
        }
        assertTrue(Files.isDirectory(dir.resolve("db-log")))
        assertTrue(Files.exists(dir.resolve("tls/shop.p12")) && Files.exists(dir.resolve("tls/shop.password")))
    }

    @Test
    fun `the proxy follows its options and keeps its certificate across renders`() {
        engine.create("shop", services = listOf("postgres"))
        val store = dir.resolve("tls/shop.p12").readText(Charsets.ISO_8859_1)
        engine.add("shop", "postgres", mapOf("query_log_max_rows" to "50", "query_log_max_size" to "1000000"))
        val command = services().getValue("postgres").list("command")
        assertEquals("50", command[command.indexOf("--max-rows") + 1])
        assertEquals("1000000", command[command.indexOf("--max-size") + 1])
        assertEquals(store, dir.resolve("tls/shop.p12").readText(Charsets.ISO_8859_1))

        val bad = runCatching { engine.add("shop", "postgres", mapOf("query_log_max_rows" to "lots")) }.exceptionOrNull()
        assertTrue(bad is GantryException, bad.toString())
        assertEquals("50", engine.serviceOptions(engine.loadApp("shop"), "postgres")["query_log_max_rows"])
    }

    @Test
    fun `with query_log=false the database is on main and no proxy runs`() {
        val bare = Engine(home = Files.createTempDirectory("gantry-dblog-off"), dockerCommand = NO_DOCKER)
        bare.create("shop")
        // No runtime is staged: an uninstalled gantry can still run a database without the query log.
        bare.add("shop", "postgres", mapOf("query_log" to "false"))
        val compose = bare.appDir("shop").resolve("compose.yml").readText()
        val services = composeServices(compose)
        assertEquals(setOf("postgres"), services.keys)
        val db = services.getValue("postgres")
        assertEquals("shop-postgres", db["container_name"])
        assertTrue(db["image"].toString().startsWith("postgres:"))
        val main =
            bare
                .loadApp("shop")
                .service("postgres")!!
                .ports
                .getValue("main")
        assertEquals(listOf("127.0.0.1:$main:5432"), (db["ports"] as List<*>).map { it.toString() })
        assertFalse("QueryLogServer" in compose)
        assertFalse(Files.exists(bare.home.resolve("runtime")))
        assertTrue(DatabaseTarget.resolve(bare, "shop", "postgres").jdbcUrl.contains(":$main/"))
    }

    private val proxyRunning = { container: String -> container.endsWith("-db") }

    @Test
    fun `once the proxy runs, tools use direct`() {
        engine.create("shop", services = listOf("postgres", "mysql"))
        listOf("postgres", "mysql").forEach { id ->
            val ports = engine.loadApp("shop").service(id)!!.ports
            val target = DatabaseTarget.resolve(engine, "shop", id) { it == "shop-$id-db" }
            assertContains(target.jdbcUrl, "127.0.0.1:${ports.getValue("direct")}/")
            assertEquals("$id-db", target.networkHost)
        }
        // Even with query_log since turned off: until the next up, the database is still the -db container.
        engine.add("shop", "postgres", mapOf("query_log" to "false"))
        assertEquals("postgres-db", DatabaseTarget.resolve(engine, "shop", "postgres", proxyRunning).networkHost)
    }

    @Test
    fun `before the next up, tools still reach the database on main`() {
        engine.create("shop", services = listOf("postgres", "mysql"))
        listOf("postgres", "mysql").forEach { id ->
            val main =
                engine
                    .loadApp("shop")
                    .service(id)!!
                    .ports
                    .getValue("main")
            val target = DatabaseTarget.resolve(engine, "shop", id) { false }
            assertContains(target.jdbcUrl, "127.0.0.1:$main/")
            assertEquals(id, target.networkHost)
        }
    }

    @Test
    fun `the live layout is read from the database container`() {
        val tools = Files.createTempDirectory("gantry-fake-docker")
        val docker = tools.resolve("docker")
        docker.writeText(
            "#!/bin/sh\nfor last; do :; done\n[ \"\$1\" = inspect ] && [ \"\$last\" = shop-postgres-db ] && echo true && exit 0\n" +
                "echo 'Error: No such container' >&2; exit 1\n",
        )
        Files.setPosixFilePermissions(
            docker,
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwx------"),
        )
        val live = Engine(home = Files.createTempDirectory("gantry-live"), dockerCommand = docker.toString(), runtimeLib = TEST_RUNTIME_LIB)
        live.create("shop", services = listOf("postgres", "mysql"))
        assertEquals("postgres-db", DatabaseTarget.resolve(live, "shop", "postgres").networkHost)
        assertEquals("mysql", DatabaseTarget.resolve(live, "shop", "mysql").networkHost)
    }

    @Test
    fun `agents and the app stay on main`() {
        engine.create("shop", services = listOf("postgres"))
        // What the app and agents are given stays on main, so their queries are logged.
        val app = engine.loadApp("shop")
        val env = engine.env(app).toMap()
        assertEquals(ports().getValue("main").toString(), env["DB_PORT"])
        val details = connectionDetails(engine, "shop").services.first { it.id == "postgres" }
        assertEquals(mapOf("main" to ports().getValue("main")), details.ports)
        assertEquals(ports().getValue("main").toString(), details.connection["DB_PORT"])
    }

    @Test
    fun `an existing app keeps its data volume and main port when the proxy is added`() {
        engine.create("shop", services = listOf("postgres"))
        val main = ports().getValue("main")
        // As an older gantry left it: no direct port.
        val portsFile = home.resolve("ports.json")
        val registry = Json.parseToJsonElement(portsFile.readText()).jsonObject.filterKeys { !it.endsWith("/direct") }
        portsFile.writeText(JsonObject(registry).toString())
        val old = engine.loadApp("shop").let { app -> app.copy(services = app.services.map { it.copy(ports = it.ports - "direct") }) }
        dir.resolve("app.json").writeText(Json.encodeToString(App.serializer(), old))
        assertNull(engine.loadApp("shop").service("postgres")!!.ports["direct"])
        assertContains(DatabaseTarget.resolve(engine, "shop", "postgres", proxyRunning).jdbcUrl, ":$main/")

        engine.refresh("shop")
        assertEquals(main, ports().getValue("main"))
        val direct = ports().getValue("direct")
        assertNotEquals(main, direct)
        val services = services()
        assertContains(services.getValue("postgres-db").list("volumes"), "postgres-data:/var/lib/postgresql/data")
        assertEquals(listOf("127.0.0.1:$main:5432"), services.getValue("postgres").list("ports"))
        val compose = dir.resolve("compose.yml").readText()
        assertContains(compose, "\nvolumes:\n  postgres-data:\n")
    }

    @Test
    fun `sync's client reaches the database, not the proxy`() {
        engine.create("shop", services = listOf("postgres", "mysql"))
        val source = SourceSettings("stage", 5432, "shop", "me")
        listOf(Dialect.Postgres to "-h postgres-db ", Dialect.MySql to "-h mysql-db ").forEach { (dialect, host) ->
            val target = DatabaseTarget.resolve(engine, "shop", dialect.id, proxyRunning)
            assertContains(copyScript(dialect, source, target, "gantry-shop_default").last(), host)
            assertContains(tableCopyScript(dialect, source, target, parseTables(dialect, listOf("t")), "gantry-shop_default").last(), host)
        }

        val plain = DatabaseTarget.resolve(engine, "shop", "postgres") { false }
        assertContains(copyScript(Dialect.Postgres, source, plain, "gantry-shop_default").last(), "-h postgres ")
    }

    @Test
    fun `a database still running under the proxy's name is stopped before up`() {
        val tools = Files.createTempDirectory("gantry-fake-docker")
        val log = tools.resolve("docker.log")
        val images = tools.resolve("images")
        val docker = tools.resolve("docker")
        docker.writeText(
            "#!/bin/sh\necho \"\$@\" >> '$log'\n" +
                "if [ \"\$1\" = inspect ]; then for last; do :; done; " +
                "grep \"^\$last \" '$images' | cut -d' ' -f2 | grep . || exit 1; fi\nexit 0\n",
        )
        Files.setPosixFilePermissions(
            docker,
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwx------"),
        )
        val live = Engine(home = tools.resolve("home"), dockerCommand = docker.toString(), runtimeLib = TEST_RUNTIME_LIB)
        live.create("shop", services = listOf("postgres"))

        images.writeText("shop-postgres postgres:16-alpine\n")
        live.docker.up("shop")
        var calls = log.readText().lines()
        val stop = calls.indexOf("stop shop-postgres")
        assertTrue(stop >= 0, calls.toString())
        assertTrue(stop < calls.indexOfFirst { "compose" in it && " up " in it }, calls.toString())

        log.writeText("")
        images.writeText("shop-postgres eclipse-temurin:17-jre\nshop-postgres-db postgres:16-alpine\n")
        live.docker.up("shop")
        assertFalse(log.readText().lines().any { it.startsWith("stop ") }, log.readText())
        // A newer proxy image is still the proxy, not the database.
        images.writeText("shop-postgres eclipse-temurin:21-jre\nshop-postgres-db postgres:16-alpine\n")
        live.docker.up("shop")
        assertFalse(log.readText().lines().any { it.startsWith("stop ") }, log.readText())

        log.writeText("")
        live.add("shop", "postgres", mapOf("query_log" to "false"))
        live.docker.up("shop")
        calls = log.readText().lines()
        assertTrue("stop shop-postgres-db" in calls, calls.toString())
        assertTrue(calls.indexOf("stop shop-postgres-db") < calls.indexOfFirst { "compose" in it && " up " in it }, calls.toString())
    }

    @Test
    fun `the proxy image is pulled before the old database is stopped, and a failed pull stops nothing`() {
        val tools = Files.createTempDirectory("gantry-fake-docker")
        val log = tools.resolve("docker.log")
        val pull = tools.resolve("pull")
        val docker = tools.resolve("docker")
        docker.writeText(
            "#!/bin/sh\necho \"\$@\" >> '$log'\n" +
                "[ \"\$1 \$2\" = 'image inspect' ] && exit 1\n" +
                "[ \"\$1\" = pull ] && exit \$(cat '$pull')\n" +
                "[ \"\$1\" = inspect ] && echo postgres:16-alpine && exit 0\nexit 0\n",
        )
        Files.setPosixFilePermissions(
            docker,
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwx------"),
        )
        val live = Engine(home = tools.resolve("home"), dockerCommand = docker.toString(), runtimeLib = TEST_RUNTIME_LIB)
        live.create("shop", services = listOf("postgres"))

        pull.writeText("1")
        val failed = runCatching { live.docker.up("shop") }.exceptionOrNull()
        assertTrue(failed is GantryException, failed.toString())
        assertContains(failed.message.orEmpty(), QUERY_LOG_IMAGE)
        assertFalse(log.readText().lines().any { it.startsWith("stop ") || " up " in it }, log.readText())

        log.writeText("")
        pull.writeText("0")
        live.docker.up("shop")
        val calls = log.readText().lines()
        val pulled = calls.indexOf("pull $QUERY_LOG_IMAGE")
        assertTrue(pulled >= 0 && pulled < calls.indexOf("stop shop-postgres"), calls.toString())
    }
}
