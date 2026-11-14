package dev.gantry.dblog

import com.github.ajalt.clikt.testing.test
import dev.gantry.cli.DbCommand
import dev.gantry.db.DatabaseExplorer
import dev.gantry.db.DatabaseTarget
import dev.gantry.engine.Engine
import dev.gantry.server.dashboardModule
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.postgresql.PGConnection
import java.io.StringReader
import java.io.StringWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The query-log proxies against real Postgres and MySQL in Docker, so skipped unless GANTRY_E2E=1. The test makes a
 * throwaway app under `build/dblog-e2e/` (its own gantry home, free ports) and removes all of it afterwards. The
 * proxies run from the jars of `./gradlew installDist`, which the test task runs first when GANTRY_E2E=1.
 *
 * The cases share one app and run in order: the first turns the Postgres query log on over existing data.
 */
@EnabledIfEnvironmentVariable(named = "GANTRY_E2E", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class QueryLogEndToEndTest {
    private val app = "dblog-e2e-" + List(6) { ('a'..'z').random() }.joinToString("")
    private val work: Path = Path.of("build", "dblog-e2e", app).toAbsolutePath()
    private val lib: Path = Path.of("build", "install", "gantry", "lib").toAbsolutePath()
    private val engine by lazy { Engine(home = work.resolve("home"), runtimeLib = lib) }
    private val ports = mutableMapOf<String, Int>()

    @BeforeAll
    fun start() {
        check(Files.isDirectory(lib)) { "$lib is missing; run ./gradlew installDist (the test task does when GANTRY_E2E=1)" }
        Files.createDirectories(work)
        engine.create(app)
        listOf("postgres/main", "postgres/direct", "mysql/main", "mysql/direct").forEach { ports[it] = freePort(ports.values) }
        engine.updatePorts { registry -> ports.forEach { (name, port) -> registry["$app/$name"] = port } }
        val limits = mapOf("query_log_max_rows" to "10")
        engine.add(app, "postgres", limits + ("query_log" to "false"))
        engine.add(app, "mysql", limits)
        engine.docker.up(app)
    }

    @AfterAll
    fun stop() {
        runCatching { engine.docker.destroy(app) }.onFailure { println("[dblog-e2e] destroy: ${it.message}") }
        if (runCatching { docker("network", "inspect", "gantry-${app}_default") }.isSuccess) {
            runCatching { docker("network", "rm", "gantry-${app}_default") }
        }
        work.toFile().deleteRecursively()
        runCatching { Files.deleteIfExists(work.parent) }
        val left = runCatching { docker("ps", "-a", "--filter", "name=$app", "--format", "{{.Names}}").trim() }.getOrDefault("?")
        val volumes = runCatching { docker("volume", "ls", "-q", "--filter", "name=$app").trim() }.getOrDefault("?")
        println("[dblog-e2e] containers left for $app: ${left.ifEmpty { "none" }}; volumes: ${volumes.ifEmpty { "none" }}")
    }

    @Test
    @Order(1)
    fun `turning the query log on keeps the data and stops the old database first`() {
        pg("disable").use { c ->
            c.exec("CREATE TABLE kept (id int PRIMARY KEY, note text)")
            c.exec("INSERT INTO kept VALUES (1, 'before the proxy')")
        }
        assertFalse(Files.exists(log("postgres")), "no proxy, no log")
        val oldDb = docker("inspect", "-f", "{{.Id}}", "$app-postgres").trim()
        val since = docker("system", "info", "-f", "{{.SystemTime}}").trim()

        engine.add(app, "postgres", mapOf("query_log" to "true"))
        engine.docker.up(app)

        assertTrue(docker("inspect", "-f", "{{.Config.Image}}", "$app-postgres").startsWith("eclipse-temurin:"))
        assertTrue(docker("inspect", "-f", "{{.Config.Image}}", "$app-postgres-db").startsWith("postgres:"))
        assertEquals("healthy", docker("inspect", "-f", "{{.State.Health.Status}}", "$app-postgres").trim())
        val until = docker("system", "info", "-f", "{{.SystemTime}}").trim()
        val died = eventTime(oldDb, "die", since, until)
        val started = eventTime("$app-postgres-db", "start", since, until)
        assertTrue(died < started, "the old database stopped ($died) before the new one started ($started)")
        assertContains(docker("logs", "$app-postgres-db"), "database system was shut down at")

        pg("disable", "e2e-kept").use { c -> assertEquals("before the proxy", c.single("SELECT note FROM kept WHERE id = 1")) }
        val entry = awaitEntry("postgres") { it.sql == "SELECT note FROM kept WHERE id = 1" }
        assertEquals(listOf(listOf<String?>("before the proxy")), entry.rows)
        assertEquals("e2e-kept", entry.client)
    }

    @Test
    @Order(2)
    fun `postgres over TLS and plaintext, with prepared statements reused past the server-prepare threshold`() {
        pg("require", "e2e-tls").use { c ->
            c.exec("CREATE TABLE items (id int PRIMARY KEY, name text)")
            c.prepareStatement("INSERT INTO items VALUES (?, ?)").use { insert ->
                (1..25).forEach {
                    insert.setInt(1, it)
                    insert.setString(2, "item-$it")
                    insert.executeUpdate()
                }
            }
            c.prepareStatement("SELECT name FROM items WHERE id = ?").use { select ->
                (1..8).forEach {
                    select.setInt(1, it)
                    select.executeQuery().use { r -> assertTrue(r.next()) }
                }
            }
        }
        pg("disable", "e2e-plain").use { c -> assertEquals("item-3", c.single("SELECT name FROM items WHERE id = 3")) }

        val selects = awaitEntries("postgres", 8) { it.sql == "SELECT name FROM items WHERE id = \$1" }
        assertEquals((1..8).map { listOf<String?>("$it") }, selects.map { it.params }.reversed())
        assertEquals((1..8).map { listOf(listOf<String?>("item-$it")) }, selects.map { it.rows }.reversed())
        assertTrue(selects.all { it.client == "e2e-tls" && it.error == null })
        val insert = awaitEntry("postgres") { it.sql.startsWith("INSERT INTO items") && it.params == listOf("25", "item-25") }
        assertEquals(1L, insert.affected)
        assertEquals("e2e-plain", awaitEntry("postgres") { it.sql == "SELECT name FROM items WHERE id = 3" }.client)
    }

    @Test
    @Order(3)
    fun `postgres keeps the first rows of a large result, its errors, COPY, cancel and LISTEN-NOTIFY`() {
        pg("require", "e2e-misc").use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT id, name FROM items ORDER BY id").use { r -> while (r.next()) Unit } }

            val missing = runCatching { c.exec("SELECT * FROM no_such_table") }.exceptionOrNull() as? SQLException
            assertEquals("42P01", missing?.sqlState)

            c.exec("CREATE TABLE copied (id int, name text)")
            val api = c.unwrap(PGConnection::class.java).copyAPI
            assertEquals(3L, api.copyIn("COPY copied FROM STDIN WITH (FORMAT csv)", StringReader("1,a\n2,b\n3,c\n")))
            val out = StringWriter()
            api.copyOut("COPY copied TO STDOUT WITH (FORMAT csv)", out)
            assertEquals("1,a\n2,b\n3,c\n", out.toString())

            val started = System.nanoTime()
            val timedOut =
                c.createStatement().use { s ->
                    s.queryTimeout = 1
                    runCatching { s.executeQuery("SELECT pg_sleep(30)") }.exceptionOrNull() as? SQLException
                }
            assertEquals("57014", timedOut?.sqlState, "cancelled by the statement timeout")
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10), "the cancel reached the database")
            assertEquals("1", c.single("SELECT 1"))

            c.autoCommit = false
            c.prepareStatement("SELECT id FROM items WHERE id > ? ORDER BY id").use { s ->
                s.fetchSize = 5
                s.setInt(1, 0)
                s.executeQuery().use { r ->
                    var n = 0
                    while (r.next()) n++
                    assertEquals(25, n)
                }
            }
            c.commit()
        }

        val listener = pg("disable", "e2e-listen")
        listener.use { l ->
            l.exec("LISTEN e2e_channel")
            pg("require", "e2e-notify").use { it.exec("NOTIFY e2e_channel, 'hello'") }
            val notes = l.unwrap(PGConnection::class.java).getNotifications(10_000)
            assertEquals(listOf("hello"), notes?.map { it.parameter })
        }

        val big = awaitEntry("postgres") { it.sql == "SELECT id, name FROM items ORDER BY id" }
        assertEquals(listOf("id", "name"), big.columns)
        assertEquals(10, big.rows?.size)
        assertEquals(listOf<String?>("10", "item-10"), big.rows?.last())
        assertEquals(25L, big.rowCount)
        assertTrue(big.truncated)
        assertEquals("42P01", awaitEntry("postgres") { it.sql == "SELECT * FROM no_such_table" }.error?.code)
        assertNull(awaitEntry("postgres") { it.sql.startsWith("COPY copied FROM STDIN") }.error)
        assertNull(awaitEntry("postgres") { it.sql.startsWith("COPY copied TO STDOUT") }.error)
        assertEquals("57014", awaitEntry("postgres") { it.sql == "SELECT pg_sleep(30)" }.error?.code)
        val fetched = awaitEntry("postgres") { it.sql == "SELECT id FROM items WHERE id > \$1 ORDER BY id" }
        assertEquals(listOf<String?>("0"), fetched.params)
        assertEquals(25L, fetched.rowCount)
        assertEquals(10, fetched.rows?.size)
        awaitEntry("postgres") { it.sql == "NOTIFY e2e_channel, 'hello'" && it.client == "e2e-notify" }
    }

    @Test
    @Order(4)
    fun `mysql over TLS and plaintext, with prepared statements, cursors, errors and a timeout`() {
        my("DISABLED", "e2e-my-first").use { c -> assertEquals("", c.status("Ssl_cipher")) }
        my("REQUIRED", "e2e-my-tls", "&useServerPrepStmts=true&cachePrepStmts=true").use { c ->
            assertTrue(c.status("Ssl_cipher").isNotEmpty(), "TLS to the proxy and on to MySQL")
            c.exec("CREATE TABLE items (id int PRIMARY KEY, name varchar(40))")
            c.prepareStatement("INSERT INTO items VALUES (?, ?)").use { insert ->
                (1..25).forEach {
                    insert.setInt(1, it)
                    insert.setString(2, "item-$it")
                    insert.executeUpdate()
                }
            }
            c.prepareStatement("SELECT name FROM items WHERE id = ?").use { select ->
                (1..8).forEach {
                    select.setInt(1, it)
                    select.executeQuery().use { r -> assertTrue(r.next()) }
                }
            }
            c.createStatement().use { s -> s.executeQuery("SELECT id, name FROM items ORDER BY id").use { r -> while (r.next()) Unit } }
            val missing = runCatching { c.exec("SELECT * FROM no_such_table") }.exceptionOrNull() as? SQLException
            assertEquals(1146, missing?.errorCode)

            val started = System.nanoTime()
            val timedOut =
                c.createStatement().use { s ->
                    s.queryTimeout = 1
                    runCatching { s.executeQuery("SELECT SLEEP(30)").use { r -> r.next() } }.exceptionOrNull()
                }
            assertTrue(timedOut is SQLTimeoutException, "timed out: $timedOut")
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10), "the KILL QUERY reached the database")
            assertEquals("1", c.single("SELECT 1"))
        }
        my("DISABLED", "e2e-my-plain").use { c ->
            assertEquals("", c.status("Ssl_cipher"))
            assertEquals("item-3", c.single("SELECT name FROM items WHERE id = 3"))
        }
        my("PREFERRED", "e2e-my-cursor", "&useServerPrepStmts=true&useCursorFetch=true").use { c ->
            c.prepareStatement("SELECT id FROM items WHERE id > ? ORDER BY id").use { s ->
                s.fetchSize = 5
                s.setInt(1, 0)
                s.executeQuery().use { r ->
                    var n = 0
                    while (r.next()) n++
                    assertEquals(25, n)
                }
            }
            assertEquals("item-4", c.single("SELECT name FROM items WHERE id = 4"))
        }

        val selects = awaitEntries("mysql", 8) { it.sql == "SELECT name FROM items WHERE id = ?" && it.client == "e2e-my-tls" }
        assertEquals((1..8).map { listOf<String?>("$it") }, selects.map { it.params }.reversed())
        assertEquals((1..8).map { listOf(listOf<String?>("item-$it")) }, selects.map { it.rows }.reversed())
        assertEquals(1L, awaitEntry("mysql") { it.sql.startsWith("INSERT INTO items") && it.params == listOf("25", "item-25") }.affected)
        val big = awaitEntry("mysql") { it.sql == "SELECT id, name FROM items ORDER BY id" }
        assertEquals(10, big.rows?.size)
        assertEquals(25L, big.rowCount)
        assertTrue(big.truncated)
        assertEquals("1146/42S02", awaitEntry("mysql") { it.sql == "SELECT * FROM no_such_table" }.error?.code)
        awaitEntry("mysql") { it.sql == "SELECT SLEEP(30)" }
        assertEquals("e2e-my-plain", awaitEntry("mysql") { it.sql == "SELECT name FROM items WHERE id = 3" }.client)
        awaitEntry("mysql") { it.sql == "SELECT id FROM items WHERE id > ? ORDER BY id" && it.params == listOf("0") }
        val after = awaitEntry("mysql") { it.sql == "SELECT name FROM items WHERE id = 4" }
        assertEquals("e2e-my-cursor", after.client)
        assertEquals(listOf(listOf<String?>("item-4")), after.rows)
    }

    @Test
    @Order(5)
    fun `gantry's own tools use direct and are not logged`() {
        val explorer = DatabaseExplorer(engine)
        listOf("postgres", "mysql").forEach { service ->
            assertEquals(ports.getValue("$service/direct"), DatabaseTarget.resolve(engine, app, service).jdbcUrl.portOf())
            val marker = "explorer_marker_$service"
            explorer.execute(app, service, "SELECT 1 AS $marker")
            appTraffic(service, "SELECT 2 AS after_$marker")
            awaitEntry(service) { it.sql == "SELECT 2 AS after_$marker" }
            assertTrue(engine.queryLog(app, service).recent(1000, marker).none { it.sql.contains("SELECT 1 AS") }, service)
        }
    }

    @Test
    @Order(6)
    fun `the CLI and the API read the log, and clear empties it while logging goes on`() {
        appTraffic("postgres", "SELECT 'cli-marker' AS seen")
        awaitEntry("postgres") { it.sql.contains("cli-marker") }
        val cli = DbCommand(engine).test(listOf("queries", app, "postgres", "--search", "cli-marker"))
        assertEquals(0, cli.statusCode, cli.output)
        assertContains(cli.output, "SELECT 'cli-marker' AS seen")
        assertContains(cli.output, "1 rows")

        testApplication {
            application { dashboardModule(this@QueryLogEndToEndTest.engine) }
            val base = "/api/apps/$app/db/mysql/queries"
            val listed = client.get("$base?search=no_such_table").bodyAsText()
            assertContains(listed, "\"code\":\"1146/42S02\"")

            assertEquals(HttpStatusCode.OK, client.delete(base).status)
            assertEquals("[]", client.get(base).bodyAsText())
        }

        assertEquals(0, DbCommand(engine).test(listOf("clear", app, "postgres")).statusCode)
        assertEquals(emptyList(), engine.queryLog(app, "postgres").recent(10))
        assertEquals(0L, Files.size(log("postgres")))

        listOf("postgres", "mysql").forEach { service ->
            appTraffic(service, "SELECT 'after clear' AS again")
            awaitEntry(service) { it.sql == "SELECT 'after clear' AS again" }
            val before =
                engine.queryLog(app, service).recent(1000).filter {
                    it.sql.contains("cli-marker") ||
                        it.sql.contains("no_such_table")
                }
            assertEquals(emptyList(), before, service)
            val lines = Files.readAllLines(log(service)).filter { it.isNotBlank() }
            assertTrue(lines.all { it.startsWith("{") }, "the file holds whole entries after the clear")
        }
    }

    private fun appTraffic(
        service: String,
        sql: String,
    ) = (if (service == "postgres") pg("disable", "e2e-app") else my("DISABLED", "e2e-app")).use { it.single(sql) }

    private fun log(service: String) = engine.appDir(app).resolve("db-log").resolve("$service.jsonl")

    private fun option(
        service: String,
        name: String,
    ) = engine.serviceOptions(engine.loadApp(app), service).getValue(name)

    private fun pg(
        sslmode: String,
        client: String = "e2e",
    ): Connection =
        DriverManager.getConnection(
            "jdbc:postgresql://127.0.0.1:${ports["postgres/main"]}/${option("postgres", "name")}?sslmode=$sslmode&ApplicationName=$client",
            option("postgres", "user"),
            option("postgres", "password"),
        )

    private fun my(
        sslMode: String,
        client: String,
        extra: String = "",
    ): Connection =
        DriverManager.getConnection(
            "jdbc:mysql://127.0.0.1:${ports["mysql/main"]}/${option("mysql", "name")}?sslMode=$sslMode" +
                "&allowPublicKeyRetrieval=true&connectionAttributes=program_name:$client$extra",
            option("mysql", "user"),
            option("mysql", "password"),
        )

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.single(sql: String): String? =
        createStatement().use { s ->
            s.executeQuery(sql).use { r ->
                assertTrue(r.next(), sql)
                r.getString(1)
            }
        }

    private fun Connection.status(name: String): String =
        createStatement().use { s -> s.executeQuery("SHOW SESSION STATUS LIKE '$name'").use { r -> if (r.next()) r.getString(2) else "" } }

    private fun String.portOf(): Int = Regex(":(\\d+)/").find(this)!!.groupValues[1].toInt()

    private fun awaitEntry(
        service: String,
        match: (QueryEntry) -> Boolean,
    ): QueryEntry = awaitEntries(service, 1, match).first()

    /** The newest [count] or more entries of [service] that [match], waiting for the proxy to write them. */
    private fun awaitEntries(
        service: String,
        count: Int,
        match: (QueryEntry) -> Boolean,
    ): List<QueryEntry> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var found = emptyList<QueryEntry>()
        while (System.nanoTime() < deadline) {
            found = engine.queryLog(app, service).recent(5000, withRows = true).filter(match)
            if (found.size >= count) return found.take(count)
            Thread.sleep(200)
        }
        val tail = engine.queryLog(app, service).recent(30).joinToString("\n") { "  ${it.sql} ${it.params} ${it.error}" }
        fail("expected $count matching $service entries, found ${found.size}; newest:\n$tail")
    }

    /** When [container] had [event] between [since] and [until], in nanoseconds. */
    private fun eventTime(
        container: String,
        event: String,
        since: String,
        until: String,
    ): Long {
        val out =
            docker(
                "events",
                "--since",
                since,
                "--until",
                until,
                "--filter",
                "container=$container",
                "--filter",
                "event=$event",
                "--format",
                "{{.TimeNano}}",
            )
        return assertNotNull(
            out
                .lines()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.toLong(),
            "no $event event for $container",
        )
    }

    private fun docker(vararg args: String): String {
        val process = ProcessBuilder(listOf(engine.dockerCommand) + args).redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("docker ${args.first()} timed out")
        }
        val text = output.get(10, TimeUnit.SECONDS)
        check(process.exitValue() == 0) { "docker ${args.joinToString(" ")} failed: $text" }
        return text
    }

    private fun freePort(taken: Collection<Int>): Int {
        while (true) {
            val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            if (port !in taken) return port
        }
    }
}
