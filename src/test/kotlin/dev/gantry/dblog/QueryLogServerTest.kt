package dev.gantry.dblog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueryLogServerTest {
    @Test
    fun `arguments are parsed into a config`(
        @org.junit.jupiter.api.io.TempDir dir: Path,
    ) {
        val pw = dir.resolve("a.password")
        java.nio.file.Files
            .writeString(pw, "secret\n")
        val c =
            parseConfig(
                arrayOf(
                    "--dialect",
                    "mysql",
                    "--listen",
                    "3306",
                    "--upstream",
                    "mysql-db:3306",
                    "--log",
                    "/db-log/mysql.jsonl",
                    "--tls",
                    "/tls/app.p12",
                    "--tls-password-file",
                    pw.toString(),
                    "--max-rows",
                    "10",
                    "--max-bytes",
                    "20",
                    "--max-size",
                    "30",
                    "--service",
                    "mysql",
                ),
            )
        assertEquals(Dialect.MYSQL, c.dialect)
        assertEquals(3306, c.listen)
        assertEquals("mysql-db", c.upstreamHost)
        assertEquals(3306, c.upstreamPort)
        assertEquals(Path.of("/db-log/mysql.jsonl"), c.log)
        assertEquals(TlsFiles(Path.of("/tls/app.p12"), "secret"), c.tls)
        assertEquals(10, c.maxRows)
        assertEquals(20, c.maxBytes)
        assertEquals(30L, c.maxSize)
        assertEquals("mysql", c.service)
    }

    @Test
    fun `defaults apply and tls is absent without flags`() {
        val c = parseConfig(arrayOf("--dialect", "postgres", "--listen", "5432", "--upstream", "h:5432", "--log", "/l.jsonl"))
        assertNull(c.tls)
        assertEquals(1000, c.maxRows)
        assertEquals(1_048_576, c.maxBytes)
        assertEquals(209_715_200L, c.maxSize)
        assertEquals("postgres", c.service)
    }

    @Test
    fun `a missing upstream is a usage error`() {
        assertThrows<UsageException> {
            parseConfig(arrayOf("--dialect", "postgres", "--listen", "5432", "--log", "/l.jsonl"))
        }
    }

    @Test
    fun `bad dialect, upstream and limits are usage errors`() {
        val base = listOf("--listen", "5432", "--log", "/l.jsonl")
        assertThrows<UsageException> { parseConfig((base + listOf("--dialect", "oracle", "--upstream", "h:1")).toTypedArray()) }
        assertThrows<UsageException> { parseConfig((base + listOf("--dialect", "postgres", "--upstream", "nohostport")).toTypedArray()) }
        assertThrows<UsageException> { parseConfig((base + listOf("--dialect", "postgres", "--upstream", "h:x")).toTypedArray()) }
        assertThrows<UsageException> {
            parseConfig((base + listOf("--dialect", "postgres", "--upstream", "h:1", "--max-rows", "many")).toTypedArray())
        }
        assertThrows<UsageException> {
            parseConfig(
                (base + listOf("--dialect", "postgres", "--upstream", "h:1", "--tls", "x")).toTypedArray(),
            )
        }
    }
}
