package dev.gantry.dblog.mysql

import dev.gantry.dblog.Certificates
import dev.gantry.dblog.LogQueue
import dev.gantry.dblog.QueryEntry
import dev.gantry.dblog.QueryLog
import dev.gantry.dblog.QueryLogWriter
import dev.gantry.dblog.TlsFiles
import dev.gantry.dblog.postgres.Limits
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
import javax.net.ssl.KeyManagerFactory
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

/** A one-connection stand-in for MySQL: [script] runs against the accepted socket; what it read is recorded. */
private class FakeMySql(
    private val script: (Socket, FakeMySql) -> Unit,
) : AutoCloseable {
    val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port get() = server.localPort
    val received = ByteArrayOutputStream()
    val done = ArrayBlockingQueue<Result<Unit>>(1)

    init {
        thread(isDaemon = true, name = "fake-mysql") {
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

    /** Reads one packet from [s] and records its wire bytes. */
    fun packet(s: Socket): ByteArray {
        val bytes = readPacket(s.getInputStream())
        synchronized(received) { received.write(bytes) }
        return bytes
    }

    fun await() = done.poll(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)!!.getOrThrow()

    override fun close() = server.close()
}

private fun readPacket(input: InputStream): ByteArray {
    val data = DataInputStream(input)
    val header = ByteArray(4).also(data::readFully)
    val length = (header[0].toInt() and 0xFF) or (header[1].toInt() and 0xFF shl 8) or (header[2].toInt() and 0xFF shl 16)
    return header + ByteArray(length).also(data::readFully)
}

private fun wire(vararg packets: MyPacket): ByteArray = packets.fold(ByteArray(0)) { acc, p -> acc + MyPackets.encode(p.seq, p.payload) }

private fun InputStream.readExactly(n: Int): ByteArray = ByteArray(n).also { DataInputStream(this).readFully(it) }

class MyProxyTest {
    private val dir = createTempDirectory("gantry-myproxy")
    private val logFile: Path = dir.resolve("db-log/mysql.jsonl")
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
        upstream: FakeMySql,
        tls: TlsFiles? = null,
    ): MyProxy {
        closeables += upstream
        val proxy = MyProxy("mysql", 0, "127.0.0.1", upstream.port, tls, Limits(1000, 1_048_576), queue, listenHost = "127.0.0.1")
        proxy.start()
        closeables += AutoCloseable { proxy.stop() }
        return proxy
    }

    private fun connect(proxy: MyProxy) =
        Socket(InetAddress.getLoopbackAddress(), proxy.port).apply {
            soTimeout = TIMEOUT_MS
            closeables += this
        }

    private fun entries(proxy: MyProxy): List<QueryEntry> {
        proxy.stop()
        queue.close()
        return QueryLog(logFile).recent(withRows = true).reversed()
    }

    private val serverCaps = My.BASE or My.SSL or My.DEPRECATE_EOF
    private val greeting = wire(My.greeting(serverCaps))
    private val authOk = wire(My.ok(seq = 2))
    private val reply = wire(My.columnCount(1), My.column("n", My.T_LONG), My.textRow("1"), My.ok(header = 0xFE))
    private val query = wire(My.query("select n from t"))
    private val quit = wire(My.packet(0) { byte(0x01) })

    private fun greetingCaps(bytes: ByteArray) = MyHandshake.greetingCapabilities(bytes.copyOfRange(4, bytes.size))

    @Test
    fun `a plaintext client and database see each other's bytes unchanged and an entry is logged`() {
        val login = wire(My.login(My.CLIENT or My.DEPRECATE_EOF, attrs = listOf("program_name" to "mysql")))
        val upstream =
            FakeMySql { s, up ->
                s.getOutputStream().write(greeting)
                up.packet(s)
                s.getOutputStream().write(authOk)
                up.packet(s)
                s.getOutputStream().write(reply)
                up.packet(s)
                assertEquals(-1, s.getInputStream().read())
            }
        val proxy = proxy(upstream)
        val client = connect(proxy)

        val seen = client.getInputStream().readExactly(greeting.size)
        assertEquals(0L, greetingCaps(seen) and My.SSL)
        assertEquals(1, greeting.indices.count { seen[it] != greeting[it] })
        client.getOutputStream().write(login)
        assertContentEquals(authOk, client.getInputStream().readExactly(authOk.size))
        client.getOutputStream().write(query)
        assertContentEquals(reply, client.getInputStream().readExactly(reply.size))
        client.getOutputStream().write(quit)
        client.close()

        upstream.await()
        assertContentEquals(login + query + quit, upstream.received.toByteArray())
        val e = entries(proxy).single()
        assertEquals("select n from t", e.sql)
        assertEquals("mysql", e.service)
        assertEquals("mysql", e.client)
        assertEquals(listOf(listOf<String?>("1")), e.rows)
    }

    @Test
    fun `a TLS client gets TLS to the database too, a plaintext one stays plaintext`() {
        val proxyTls = Certificates.ensure(dir, "shop")
        val dbTls = Certificates.ensure(dir, "db")
        val caps = My.CLIENT or My.DEPRECATE_EOF or My.SSL
        val sslRequest = wire(My.sslRequest(caps))
        val tlsLogin = wire(My.login(caps, seq = 2))
        val plainLogin = wire(My.login(My.CLIENT or My.DEPRECATE_EOF))

        val tlsUpstream =
            FakeMySql { s, up ->
                s.getOutputStream().write(greeting)
                up.packet(s)
                val secure =
                    (serverContext(dbTls).socketFactory.createSocket(s, "localhost", s.port, true) as SSLSocket).apply {
                        useClientMode = false
                        soTimeout = TIMEOUT_MS
                        startHandshake()
                    }
                up.packet(secure)
                secure.getOutputStream().write(wire(My.ok(seq = 3)))
                up.packet(secure)
                secure.getOutputStream().write(reply)
                up.packet(secure)
            }
        val tlsProxy = proxy(tlsUpstream, proxyTls)
        val plain = connect(tlsProxy)
        assertTrue(greetingCaps(plain.getInputStream().readExactly(greeting.size)) and My.SSL != 0L)
        plain.getOutputStream().write(sslRequest)
        val secure =
            (trusting(proxyTls).socketFactory.createSocket(plain, "localhost", tlsProxy.port, true) as SSLSocket).apply {
                sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                soTimeout = TIMEOUT_MS
                startHandshake()
            }
        closeables += secure
        secure.getOutputStream().write(tlsLogin)
        assertContentEquals(wire(My.ok(seq = 3)), secure.getInputStream().readExactly(authOk.size))
        secure.getOutputStream().write(query)
        assertContentEquals(reply, secure.getInputStream().readExactly(reply.size))
        secure.getOutputStream().write(quit)
        tlsUpstream.await()
        secure.close()
        assertContentEquals(sslRequest + tlsLogin + query + quit, tlsUpstream.received.toByteArray())

        val plainUpstream =
            FakeMySql { s, up ->
                s.getOutputStream().write(greeting)
                up.packet(s)
                s.getOutputStream().write(authOk)
                up.packet(s)
                assertEquals(-1, s.getInputStream().read())
            }
        val plainProxy = proxy(plainUpstream, proxyTls)
        val client = connect(plainProxy)
        assertTrue(greetingCaps(client.getInputStream().readExactly(greeting.size)) and My.SSL != 0L)
        client.getOutputStream().write(plainLogin)
        assertContentEquals(authOk, client.getInputStream().readExactly(authOk.size))
        client.getOutputStream().write(quit)
        client.close()
        plainUpstream.await()
        // Read as plaintext packets by the database: no TLS on its leg.
        assertContentEquals(plainLogin + quit, plainUpstream.received.toByteArray())

        assertEquals("select n from t", entries(tlsProxy).single().sql)
    }

    @Test
    fun `a client that disconnects mid-result closes the database leg and logs the partial entry`() {
        val partial = wire(My.columnCount(1), My.column("n", My.T_LONG), My.textRow("1"))
        val upstream =
            FakeMySql { s, up ->
                s.getOutputStream().write(greeting)
                up.packet(s)
                s.getOutputStream().write(authOk)
                up.packet(s)
                s.getOutputStream().write(partial)
                assertEquals(-1, s.getInputStream().read())
            }
        val proxy = proxy(upstream)
        val client = connect(proxy)
        client.getInputStream().readExactly(greeting.size)
        client.getOutputStream().write(wire(My.login(My.CLIENT or My.DEPRECATE_EOF)))
        client.getInputStream().readExactly(authOk.size)
        client.getOutputStream().write(wire(My.query("select n from big")))
        client.getInputStream().readExactly(partial.size)
        client.close()

        upstream.await()
        val e = entries(proxy).single()
        assertEquals("select n from big", e.sql)
        assertEquals(listOf(listOf<String?>("1")), e.rows)
        assertTrue(e.truncated)
    }

    @Test
    fun `an accept failure that is not a close keeps the proxy accepting`() {
        val upstream = FakeMySql { s, _ -> s.getOutputStream().write(greeting) }
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
            val proxy = MyProxy("mysql", 0, "127.0.0.1", upstream.port, null, Limits(1000, 1000), queue, listenHost = "127.0.0.1") { flaky }
            proxy.start()
            closeables += AutoCloseable { proxy.stop() }
            val seen = connect(proxy).getInputStream().readExactly(greeting.size)
            assertEquals(greeting.size, seen.size)
        } finally {
            System.setErr(stderr)
        }
        assertTrue(failures.get() >= 3)
        assertEquals(1, Regex("Too many open files").findAll(err.toString()).count())
    }

    @Test
    fun `an unreachable database gets the client an error packet`() {
        val closed = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { it.close() }
        val proxy = MyProxy("mysql", 0, "127.0.0.1", closed.localPort, null, Limits(1000, 1000), queue, listenHost = "127.0.0.1")
        proxy.start()
        closeables += AutoCloseable { proxy.stop() }
        val client = connect(proxy)

        val packet = readPacket(client.getInputStream())
        assertEquals(0xFF, packet[4].toInt() and 0xFF)
        assertTrue(String(packet, 7, packet.size - 7).contains("cannot reach the database"))
    }

    private fun serverContext(tls: TlsFiles): SSLContext {
        val password = tls.password.toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(tls.keyStore).use { load(it, password) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
    }

    private fun trusting(tls: TlsFiles): SSLContext {
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(tls.keyStore).use { load(it, tls.password.toCharArray()) } }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        trust.setCertificateEntry("gantry", store.getCertificate(store.aliases().toList().single()))
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }
}
