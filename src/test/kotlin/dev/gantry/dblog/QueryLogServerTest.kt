package dev.gantry.dblog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueryLogServerTest {
    @Test
    fun `arguments are parsed into a config`(
        @TempDir dir: Path,
    ) {
        val pw = dir.resolve("a.password")
        Files.writeString(pw, "secret\n")
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
                    "--health-port",
                    "8086",
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
        assertEquals(8086, c.healthPort)
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

    private val base = listOf("--dialect", "postgres", "--upstream", "h:5432", "--log", "/l.jsonl")

    private fun problem(vararg args: String) = assertThrows<UsageException> { parseConfig(args.toList().toTypedArray()) }.message

    @Test
    fun `unknown flags, flags without a value and out-of-range ports are usage errors`() {
        assertEquals("unknown argument --nope", problem(*(base + listOf("--listen", "1", "--nope", "x")).toTypedArray()))
        assertEquals("--listen needs a value", problem(*(base + "--listen").toTypedArray()))
        assertEquals("--listen must be 1-65535", problem(*(base + listOf("--listen", "0")).toTypedArray()))
        assertEquals("--listen must be 1-65535", problem(*(base + listOf("--listen", "70000")).toTypedArray()))
        assertEquals("--health-port must be 1-65535", problem(*(base + listOf("--listen", "1", "--health-port", "0")).toTypedArray()))
        assertEquals(
            "--upstream port must be 1-65535",
            problem("--dialect", "postgres", "--listen", "1", "--log", "/l", "--upstream", "h:65536"),
        )
    }

    @Test
    fun `a password file alone, or one that cannot be named, is a usage error`(
        @TempDir dir: Path,
    ) {
        val pw = dir.resolve("p").also { Files.writeString(it, "x") }
        assertEquals(
            "--tls and --tls-password-file go together",
            problem(*(base + listOf("--listen", "1", "--tls-password-file", pw.toString())).toTypedArray()),
        )
        problem(*(base + listOf("--listen", "1", "--tls", "/t.p12", "--tls-password-file", "bad\u0000path")).toTypedArray())
    }

    @Test
    fun `an IPv6 upstream host loses its brackets`() {
        val c = parseConfig(arrayOf("--dialect", "postgres", "--listen", "1", "--log", "/l", "--upstream", "[::1]:5432"))
        assertEquals("::1", c.upstreamHost)
        assertEquals(5432, c.upstreamPort)
        assertNull(c.healthPort)
    }
}
