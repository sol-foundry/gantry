package dev.gantry.dblog

import dev.gantry.dblog.mysql.MyProxy
import dev.gantry.dblog.postgres.Limits
import dev.gantry.dblog.postgres.PgProxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val DEFAULT_MAX_ROWS = 1000
private const val DEFAULT_MAX_BYTES = 1_048_576
private const val DEFAULT_MAX_SIZE = 209_715_200L
private val VALUE_FLAGS =
    setOf(
        "--dialect",
        "--listen",
        "--upstream",
        "--log",
        "--tls",
        "--tls-password-file",
        "--max-rows",
        "--max-bytes",
        "--max-size",
        "--service",
    )

enum class Dialect { POSTGRES, MYSQL }

class UsageException(
    message: String,
) : RuntimeException(message)

data class QueryLogConfig(
    val dialect: Dialect,
    val listen: Int,
    val upstreamHost: String,
    val upstreamPort: Int,
    val log: Path,
    val tls: TlsFiles?,
    val maxRows: Int,
    val maxBytes: Int,
    val maxSize: Long,
    val service: String,
)

private fun fail(problem: String): Nothing = throw UsageException(problem)

fun parseConfig(args: Array<String>): QueryLogConfig {
    val values = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a !in VALUE_FLAGS) fail("unknown argument $a")
        if (i + 1 >= args.size) fail("$a needs a value")
        values[a] = args[++i]
        i++
    }

    fun number(flag: String): Int? =
        values[flag]?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: fail("$flag must be a positive number") }

    val dialect =
        when (values["--dialect"] ?: fail("--dialect is required")) {
            "postgres" -> Dialect.POSTGRES
            "mysql" -> Dialect.MYSQL
            else -> fail("--dialect must be postgres or mysql")
        }
    val upstream = values["--upstream"]?.takeIf { it.isNotBlank() } ?: fail("--upstream is required")
    val colon = upstream.lastIndexOf(':')
    if (colon <= 0) fail("--upstream must be host:port")
    val upstreamPort = upstream.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: fail("--upstream port must be a number")
    val tlsPath = values["--tls"]
    val passwordFile = values["--tls-password-file"]
    if ((tlsPath == null) != (passwordFile == null)) fail("--tls and --tls-password-file go together")
    val tls =
        tlsPath?.let {
            val password =
                try {
                    Files.readString(Path.of(passwordFile!!)).trim()
                } catch (e: java.io.IOException) {
                    fail("cannot read --tls-password-file: ${e.message}")
                }
            TlsFiles(Path.of(it), password)
        }
    return QueryLogConfig(
        dialect = dialect,
        listen = number("--listen") ?: fail("--listen is required"),
        upstreamHost = upstream.substring(0, colon),
        upstreamPort = upstreamPort,
        log = Path.of(values["--log"] ?: fail("--log is required")),
        tls = tls,
        maxRows = number("--max-rows") ?: DEFAULT_MAX_ROWS,
        maxBytes = number("--max-bytes") ?: DEFAULT_MAX_BYTES,
        maxSize =
            values["--max-size"]?.let { it.toLongOrNull()?.takeIf { n -> n > 0 } ?: fail("--max-size must be a positive number") }
                ?: DEFAULT_MAX_SIZE,
        service = values["--service"] ?: dialect.name.lowercase(),
    )
}

private fun usage(problem: String): Nothing {
    System.err.println("gantry-dblog: $problem")
    System.err.println(
        "usage: gantry-dblog --dialect postgres|mysql --listen PORT --upstream HOST:PORT --log FILE " +
            "[--tls P12 --tls-password-file FILE] [--max-rows N] [--max-bytes N] [--max-size N] [--service NAME]",
    )
    exitProcess(2)
}

fun main(args: Array<String>) {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    val c =
        try {
            parseConfig(args)
        } catch (e: UsageException) {
            usage(e.message ?: "bad arguments")
        }
    Files.createDirectories(c.log.toAbsolutePath().parent)
    val writer = QueryLogWriter(c.log, c.maxSize)
    val queue = LogQueue(writer)
    val limits = Limits(c.maxRows, c.maxBytes)
    val stop: () -> Unit
    when (c.dialect) {
        Dialect.POSTGRES ->
            PgProxy(c.service, c.listen, c.upstreamHost, c.upstreamPort, c.tls, limits, queue).also {
                it.start()
                stop = it::stop
            }
        Dialect.MYSQL ->
            MyProxy(c.service, c.listen, c.upstreamHost, c.upstreamPort, c.tls, limits, queue).also {
                it.start()
                stop = it::stop
            }
    }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching(stop)
            runCatching { queue.close() }
            runCatching { writer.close() }
        },
    )
    println("ready")
    System.out.flush()
    Thread.currentThread().join()
}
