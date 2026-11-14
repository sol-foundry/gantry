package dev.gantry.cli

import com.github.ajalt.clikt.testing.test
import dev.gantry.dblog.QueryEntry
import dev.gantry.dblog.QueryLogWriter
import dev.gantry.dblog.queryLog
import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.NO_DOCKER
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DbCliTest {
    private val lib: Path = Files.createTempDirectory("gantry-lib").also { it.resolve("gantry.jar").writeText("jar") }
    private val engine =
        Engine(home = Files.createTempDirectory("gantry-cli-db"), dockerCommand = NO_DOCKER, runtimeLib = lib).apply {
            create("shop", services = listOf("postgres"))
        }

    private fun entry(
        id: String,
        time: String,
        sql: String,
    ) = QueryEntry(
        id = id,
        time = time,
        durationMs = 2.5,
        service = "postgres",
        connectionId = 1,
        client = null,
        sql = sql,
        params = emptyList(),
        columns = null,
        rows = null,
        rowCount = 3,
        affected = null,
        command = null,
        error = null,
        truncated = false,
    )

    private fun seed(
        app: String = "shop",
        service: String = "postgres",
    ) {
        val file = engine.appDir(app).resolve("db-log").resolve("$service.jsonl")
        QueryLogWriter(file, 1_000_000).use { w ->
            w.append(entry("1", "2026-11-14T10:00:00Z", "select * from users"))
            w.append(entry("2", "2026-11-14T10:01:00Z", "update orders\n   set x = 1"))
            w.append(entry("3", "2026-11-14T10:02:00Z", "select * from users where id = 1"))
        }
    }

    @Test
    fun `gantry db queries prints newest last with filters`() {
        seed()
        val all = DbCommand(engine).test("queries shop")
        assertEquals(0, all.statusCode, all.output)
        val lines = all.output.lines().filter { it.isNotBlank() }
        assertContains(lines.first(), "SQL")
        assertEquals(
            listOf("select * from users", "update orders set x = 1", "select * from users where id = 1"),
            lines.drop(1).map {
                it.substringAfter("3 rows").trim()
            },
        )

        val searched = DbCommand(engine).test("queries shop postgres --search USERS --limit 1").output
        assertContains(searched, "where id = 1")
        assertFalse("update orders" in searched)

        val window = DbCommand(engine).test("queries shop --from 2026-11-14T10:01:00Z --to 2026-11-14T10:01:30Z").output
        assertContains(window, "update orders")
        assertFalse("select * from users" in window)

        assertFailsWith<GantryException> { DbCommand(engine).test("queries shop --since yesterday") }
        val since = DbCommand(engine).test("queries shop --since 2026-11-14T10:02:00Z").output
        assertContains(since, "where id = 1")
        assertFalse("update orders" in since)
    }

    @Test
    fun `service is optional with one database and required with several, and must be query-logged`() {
        seed()
        engine.add("shop", "mysql")
        val ambiguous = DbCommand(engine).test("queries shop")
        assertEquals(1, ambiguous.statusCode)
        assertContains(ambiguous.output, "postgres, mysql")

        engine.add("shop", "valkey")
        val notDatabase = assertFailsWith<GantryException> { DbCommand(engine).test("queries shop valkey") }
        assertContains(notDatabase.message.orEmpty(), "not a query-logged database")

        engine.create("bare", services = listOf("valkey"))
        val none = assertFailsWith<GantryException> { DbCommand(engine).test("queries bare") }
        assertContains(none.message.orEmpty(), "no database with the query log on")
    }

    @Test
    fun `gantry db clear empties the log`() {
        seed()
        val result = DbCommand(engine).test("clear shop")
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.output, "Cleared")
        assertTrue(engine.queryLog("shop", "postgres").recent().isEmpty())
        assertFailsWith<GantryException> { DbCommand(engine).test("clear shop mysql") }
    }
}
