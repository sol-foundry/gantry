package dev.gantry.dblog.postgres

import dev.gantry.dblog.Certificates
import dev.gantry.dblog.LogQueue
import dev.gantry.dblog.QueryEntry
import dev.gantry.dblog.QueryLog
import dev.gantry.dblog.QueryLogWriter
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 5_000

/** A one-connection stand-in for Postgres: [script] runs against the accepted socket; what it read is recorded. */
private class FakeUpstream(
    private val script: (Socket, FakeUpstream) -> Unit,
) : AutoCloseable {
    val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port get() = server.localPort
    val received = ByteArrayOutputStream()
    val done = ArrayBlockingQueue<Result<Unit>>(1)

    init {
        thread(isDaemon = true, name = "fake-upstream") {
            val result =
                runCatching {
                    server.accept().use { s ->
                        s.soTimeout = TIMEOUT_MS
                        script(s, this)
                    }
                }
            done.offer(result)
        }
    }

    fun read(
        s: Socket,
        n: Int,
    ): ByteArray {
        val bytes = ByteArray(n)
        DataInputStream(s.getInputStream()).readFully(bytes)
        synchronized(received) { received.write(bytes) }
        return bytes
    }

    /** Reads one typed frame (or an untyped startup frame) and returns its type. */
    fun frame(
        s: Socket,
        untyped: Boolean = false,
    ): Char {
        val type = if (untyped) PG_UNTYPED else read(s, 1)[0].toInt().toChar()
        val length = PgReader(read(s, 4)).int32()
        read(s, length - 4)
        return type
    }

    fun await() = done.poll(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)!!.getOrThrow()

    override fun close() = server.close()
}

private fun bytes(vararg messages: PgMessage): ByteArray = messages.fold(ByteArray(0)) { acc, m -> acc + PgFrames.encode(m) }

private fun InputStream.readExactly(n: Int): ByteArray = ByteArray(n).also { DataInputStream(this).readFully(it) }

class PgProxyTest {
    private val dir = createTempDirectory("gantry-pgproxy")
    private val logFile: Path = dir.resolve("db-log/postgres.jsonl")
    private val writer = QueryLogWriter(logFile, 10_000_000)
    private val queue = LogQueue(writer)
    private val closeables = ArrayList<AutoCloseable>()

    @AfterTest
    fun cleanup() {
        closeables.asReversed().forEach { runCatching { it.close() } }
        queue.close()
        writer.close()
    }

    private fun proxy(
        upstream: FakeUpstream,
        tls: dev.gantry.dblog.TlsFiles? = null,
    ): PgProxy {
        closeables += upstream
        val proxy = PgProxy("postgres", 0, "127.0.0.1", upstream.port, tls, Limits(1000, 1_048_576), queue, listenHost = "127.0.0.1")
        proxy.start()
        closeables += AutoCloseable { proxy.stop() }
        return proxy
    }

    private fun connect(proxy: PgProxy) =
        Socket(InetAddress.getLoopbackAddress(), proxy.port).apply {
            soTimeout = TIMEOUT_MS
            closeables += this
        }

    /** Stops the proxy, then drains the queue into the log. */
    private fun entries(proxy: PgProxy): List<QueryEntry> {
        proxy.stop()
        queue.close()
        return QueryLog(logFile).recent(withRows = true).reversed()
    }

    private val startup = bytes(Pg.startup("user" to "app", "database" to "shop", "application_name" to "psql"))
    private val serverHello = bytes(PgMessage('R', PgPayload().int32(0).toByteArray()), Pg.ready())
    private val queryReply =
        bytes(Pg.rowDescription("n" to 23), Pg.dataRow("1"), Pg.dataRow("2"), Pg.commandComplete("SELECT 2"), Pg.ready())

    private fun scripted(): FakeUpstream =
        FakeUpstream { s, up ->
            up.frame(s, untyped = true)
            s.getOutputStream().write(serverHello)
            up.frame(s)
            s.getOutputStream().write(queryReply)
            up.frame(s)
            assertEquals(-1, s.getInputStream().read())
        }

    @Test
    fun `plaintext client and upstream see each other's bytes unchanged and an entry is logged`() {
        val upstream = scripted()
        val proxy = proxy(upstream)
        val client = connect(proxy)
        val sent = startup + bytes(Pg.query("select n from t")) + bytes(PgMessage('X', ByteArray(0)))

        client.getOutputStream().write(startup)
        assertContentEquals(serverHello, client.getInputStream().readExactly(serverHello.size))
        client.getOutputStream().write(bytes(Pg.query("select n from t")))
        assertContentEquals(queryReply, client.getInputStream().readExactly(queryReply.size))
        client.getOutputStream().write(bytes(PgMessage('X', ByteArray(0))))
        client.close()

        upstream.await()
        assertContentEquals(sent, upstream.received.toByteArray())
        val e = entries(proxy).single()
        assertEquals("select n from t", e.sql)
        assertEquals("postgres", e.service)
        assertEquals("psql", e.client)
        assertEquals(listOf(listOf<String?>("1"), listOf("2")), e.rows)
        assertEquals(2L, e.rowCount)
    }

    @Test
    fun `a GSSENCRequest is declined and a plaintext request without TLS gets N`() {
        val upstream = scripted()
        val proxy = proxy(upstream)
        val client = connect(proxy)
        client.getOutputStream().write(PgPayload().int32(8).int32(80877104).toByteArray())
        assertEquals('N'.code, client.getInputStream().read())
        client.getOutputStream().write(PgPayload().int32(8).int32(80877103).toByteArray())
        assertEquals('N'.code, client.getInputStream().read())
        client.getOutputStream().write(startup)
        assertContentEquals(serverHello, client.getInputStream().readExactly(serverHello.size))
        client.getOutputStream().write(bytes(Pg.query("select n from t")))
        client.getInputStream().readExactly(queryReply.size)
        client.getOutputStream().write(bytes(PgMessage('X', ByteArray(0))))
        client.close()

        upstream.await()
        assertContentEquals(
            startup + bytes(Pg.query("select n from t")) + bytes(PgMessage('X', ByteArray(0))),
            upstream.received.toByteArray(),
        )
    }

    @Test
    fun `an SSLRequest is answered S and the session continues over TLS`() {
        val tls = Certificates.ensure(dir, "shop")
        val upstream = scripted()
        val proxy = proxy(upstream, tls)
        val plain = connect(proxy)
        plain.getOutputStream().write(PgPayload().int32(8).int32(80877103).toByteArray())
        assertEquals('S'.code, plain.getInputStream().read())

        val ssl =
            (trusting(tls).socketFactory.createSocket(plain, "localhost", proxy.port, true) as SSLSocket).apply {
                sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                soTimeout = TIMEOUT_MS
                startHandshake()
            }
        closeables += ssl
        ssl.getOutputStream().write(startup)
        assertContentEquals(serverHello, ssl.getInputStream().readExactly(serverHello.size))
        ssl.getOutputStream().write(bytes(Pg.query("select n from t")))
        assertContentEquals(queryReply, ssl.getInputStream().readExactly(queryReply.size))
        ssl.getOutputStream().write(bytes(PgMessage('X', ByteArray(0))))
        ssl.close()

        upstream.await()
        assertContentEquals(
            startup + bytes(Pg.query("select n from t")) + bytes(PgMessage('X', ByteArray(0))),
            upstream.received.toByteArray(),
        )
        assertEquals("select n from t", entries(proxy).single().sql)
    }

    @Test
    fun `a cancel request is relayed to the database`() {
        val cancel =
            PgPayload()
                .int32(16)
                .int32(80877102)
                .int32(4242)
                .int32(-559038737)
                .toByteArray()
        val upstream =
            FakeUpstream { s, up ->
                up.read(s, 16)
                assertEquals(-1, s.getInputStream().read())
            }
        val proxy = proxy(upstream)
        val client = connect(proxy)
        client.getOutputStream().write(cancel)
        assertEquals(-1, client.getInputStream().read())

        upstream.await()
        assertContentEquals(cancel, upstream.received.toByteArray())
        assertTrue(entries(proxy).isEmpty())
    }

    @Test
    fun `an accept failure that is not a close keeps the proxy accepting`() {
        val upstream = scripted()
        closeables += upstream
        val failures = AtomicInteger()
        val flaky =
            object : ServerSocket() {
                override fun accept(): Socket {
                    if (failures.getAndIncrement() < 2) throw IOException("Too many open files")
                    return super.accept()
                }
            }
        val err = ByteArrayOutputStream()
        val stderr = System.err
        System.setErr(PrintStream(err, true))
        try {
            val proxy =
                PgProxy("postgres", 0, "127.0.0.1", upstream.port, null, Limits(1000, 1000), queue, listenHost = "127.0.0.1") { flaky }
            proxy.start()
            closeables += AutoCloseable { proxy.stop() }
            val client = connect(proxy)
            client.getOutputStream().write(startup)
            assertContentEquals(serverHello, client.getInputStream().readExactly(serverHello.size))
        } finally {
            System.setErr(stderr)
        }
        assertTrue(failures.get() >= 3)
        assertEquals(1, Regex("Too many open files").findAll(err.toString()).count())
    }

    @Test
    fun `a client that disconnects mid-result closes the upstream and logs the partial entry`() {
        val partial = bytes(Pg.rowDescription("n" to 23), Pg.dataRow("1"), Pg.dataRow("2"))
        val upstream =
            FakeUpstream { s, up ->
                up.frame(s, untyped = true)
                s.getOutputStream().write(serverHello)
                up.frame(s)
                s.getOutputStream().write(partial)
                assertEquals(-1, s.getInputStream().read())
            }
        val proxy = proxy(upstream)
        val client = connect(proxy)
        client.getOutputStream().write(startup)
        client.getInputStream().readExactly(serverHello.size)
        client.getOutputStream().write(bytes(Pg.query("select n from big")))
        client.getInputStream().readExactly(partial.size)
        client.close()

        upstream.await()
        val e = entries(proxy).single()
        assertEquals("select n from big", e.sql)
        assertEquals(listOf(listOf<String?>("1"), listOf("2")), e.rows)
        assertTrue(e.truncated)
    }

    @Test
    fun `an unreachable database gets the client an error response`() {
        val closed = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { it.close() }
        val proxy = PgProxy("postgres", 0, "127.0.0.1", closed.localPort, null, Limits(1000, 1000), queue, listenHost = "127.0.0.1")
        proxy.start()
        closeables += AutoCloseable { proxy.stop() }
        val client = connect(proxy)
        client.getOutputStream().write(startup)
        assertEquals('E'.code, client.getInputStream().read())
    }

    private fun trusting(tls: dev.gantry.dblog.TlsFiles): SSLContext {
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(tls.keyStore).use { load(it, tls.password.toCharArray()) } }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        trust.setCertificateEntry("gantry", store.getCertificate(store.aliases().toList().single()))
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }
}
