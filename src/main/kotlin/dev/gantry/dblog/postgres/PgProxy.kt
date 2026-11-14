package dev.gantry.dblog.postgres

import dev.gantry.dblog.LogQueue
import dev.gantry.dblog.TlsFiles
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Listens on [listen] and relays each client's Postgres connection to [upstreamHost]:[upstreamPort] byte for byte,
 * recording its statements into [queue]. It answers SSLRequest itself, terminating TLS with [tls] (or declining when
 * null), declines GSSENCRequest, and passes CancelRequest on over a fresh connection. The upstream leg is plaintext.
 */
class PgProxy(
    private val service: String,
    private val listen: Int,
    private val upstreamHost: String,
    private val upstreamPort: Int,
    tls: TlsFiles?,
    private val limits: Limits,
    private val queue: LogQueue,
    private val listenHost: String = "0.0.0.0",
) {
    private val ssl: SSLContext? = tls?.let(::sslContext)
    private val ids = AtomicLong()
    private val connections = ConcurrentHashMap.newKeySet<Connection>()

    @Volatile
    private var server: ServerSocket? = null
    private var acceptor: Thread? = null

    /** The port it listens on, once started (useful when [listen] is 0). */
    val port: Int get() = server?.localPort ?: error("proxy not started")

    fun start() {
        val socket =
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(listenHost, listen))
            }
        server = socket
        acceptor =
            thread(isDaemon = true, name = "pgproxy-accept-$service") {
                while (!socket.isClosed) {
                    val client =
                        try {
                            socket.accept()
                        } catch (_: IOException) {
                            break
                        }
                    val connection = Connection(client, ids.incrementAndGet())
                    connections += connection
                    connection.thread = thread(isDaemon = true, name = "pgproxy-${connection.id}") { connection.run() }
                }
            }
    }

    /** Stops listening, drops every open connection and waits for their last entries to reach the queue. */
    fun stop() {
        server?.close()
        acceptor?.join(JOIN_MS)
        connections.forEach { it.closeSockets() }
        connections.forEach { it.thread?.join(JOIN_MS) }
    }

    private inner class Connection(
        private val raw: Socket,
        val id: Long,
    ) {
        @Volatile
        var thread: Thread? = null

        @Volatile
        private var tlsSocket: SSLSocket? = null

        @Volatile
        private var upstream: Socket? = null

        fun run() {
            try {
                raw.tcpNoDelay = true
                raw.soTimeout = STARTUP_TIMEOUT_MS
                var client: Socket = raw
                while (true) {
                    val frame = readStartup(client.getInputStream()) ?: return
                    when (PgReader(frame).apply { int32() }.int32()) {
                        SSL_REQUEST ->
                            if (ssl == null || client is SSLSocket) {
                                answer(client, 'N')
                            } else {
                                answer(client, 'S')
                                client = handshake(ssl)
                            }
                        GSSENC_REQUEST -> answer(client, 'N')
                        CANCEL_REQUEST -> return cancel(frame)
                        else -> {
                            raw.soTimeout = 0
                            return relay(client, frame)
                        }
                    }
                }
            } catch (_: Exception) {
                // The client went away or broke the protocol; nothing to relay.
            } finally {
                closeSockets()
                connections -= this
            }
        }

        private fun handshake(context: SSLContext): SSLSocket {
            val socket = context.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
            tlsSocket = socket
            socket.useClientMode = false
            socket.startHandshake()
            return socket
        }

        private fun cancel(frame: ByteArray) {
            connectUpstream().use { it.getOutputStream().write(frame) }
        }

        private fun relay(
            client: Socket,
            startup: ByteArray,
        ) {
            val db =
                try {
                    connectUpstream().also { upstream = it }
                } catch (e: IOException) {
                    client.getOutputStream().write(
                        fatal("gantry query-log proxy cannot reach the database at $upstreamHost:$upstreamPort: ${e.message}"),
                    )
                    return
                }
            val session = PgSession(service, id, limits, queue::offer)
            val clientFrames = PgFrameReader(startupPhase = true)
            val serverFrames = PgFrameReader(startupPhase = false)
            guarded { clientFrames.feed(startup).forEach(session::fromClient) }
            db.getOutputStream().write(startup)

            val back =
                thread(isDaemon = true, name = "pgproxy-$id-server") {
                    try {
                        pump(db.getInputStream(), client.getOutputStream(), observeFirst = false) { b, n ->
                            serverFrames.feed(b, 0, n).forEach(session::fromServer)
                        }
                    } finally {
                        closeSockets()
                    }
                }
            try {
                // Client frames are observed before they go upstream, so a fast reply always finds its request queued.
                pump(client.getInputStream(), db.getOutputStream(), observeFirst = true) { b, n ->
                    clientFrames.feed(b, 0, n).forEach(session::fromClient)
                }
            } finally {
                closeSockets()
                back.join(JOIN_MS)
                guarded { session.close() }
            }
        }

        fun closeSockets() {
            runCatching { raw.close() }
            runCatching { tlsSocket?.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun connectUpstream(): Socket =
        Socket().apply {
            connect(InetSocketAddress(upstreamHost, upstreamPort), CONNECT_TIMEOUT_MS)
            tcpNoDelay = true
        }

    private companion object {
        const val SSL_REQUEST = 80877103
        const val GSSENC_REQUEST = 80877104
        const val CANCEL_REQUEST = 80877102
        const val MAX_STARTUP = 10_000
        const val STARTUP_TIMEOUT_MS = 30_000
        const val CONNECT_TIMEOUT_MS = 10_000
        const val JOIN_MS = 10_000L

        /** One untyped startup-phase frame, length included, or null when the stream ends or the length is absurd. */
        fun readStartup(input: InputStream): ByteArray? {
            val data = DataInputStream(input)
            val length =
                try {
                    data.readInt()
                } catch (_: IOException) {
                    return null
                }
            if (length < 8 || length > MAX_STARTUP) return null
            return PgPayload().int32(length).bytes(ByteArray(length - 4).also(data::readFully)).toByteArray()
        }

        fun answer(
            socket: Socket,
            code: Char,
        ) {
            socket.getOutputStream().apply {
                write(code.code)
                flush()
            }
        }

        /** Copies [input] to [output] until either ends, handing each chunk to [observe] before or after it is sent. */
        fun pump(
            input: InputStream,
            output: OutputStream,
            observeFirst: Boolean,
            observe: (ByteArray, Int) -> Unit,
        ) {
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) return
                    if (observeFirst) guarded { observe(buffer, n) }
                    output.write(buffer, 0, n)
                    output.flush()
                    if (!observeFirst) guarded { observe(buffer, n) }
                }
            } catch (_: IOException) {
                // Either side closed.
            }
        }

        /** Recording must never break the relay. */
        inline fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (_: Exception) {
                // Ignored: the bytes are relayed regardless.
            }
        }

        fun fatal(message: String): ByteArray =
            PgFrames.encode(
                PgMessage(
                    'E',
                    PgPayload()
                        .byte('S'.code)
                        .cstring("FATAL")
                        .byte('V'.code)
                        .cstring("FATAL")
                        .byte('C'.code)
                        .cstring("08006")
                        .byte('M'.code)
                        .cstring(message)
                        .byte(0)
                        .toByteArray(),
                ),
            )

        fun sslContext(tls: TlsFiles): SSLContext {
            val password = tls.password.toCharArray()
            val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(tls.keyStore).use { load(it, password) } }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        }
    }
}
