package dev.gantry.dblog.mysql

import dev.gantry.dblog.LogQueue
import dev.gantry.dblog.Relay
import dev.gantry.dblog.TlsFiles
import dev.gantry.dblog.postgres.Limits
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Listens on [listen] and relays each client's MySQL connection to [upstreamHost]:[upstreamPort] byte for byte,
 * recording its statements into [queue]. The server greeting advertises TLS when [tls] is set (and MySQL offers it);
 * a client that asks for TLS gets it terminated here with [tls], and the proxy then speaks TLS to MySQL too (trusting
 * its self-signed certificate); a plaintext client stays plaintext on both legs. Authentication is relayed untouched.
 * The greeting's capability flags are the only bytes changed: TLS as above, compression and optional result metadata
 * withdrawn.
 */
class MyProxy(
    private val service: String,
    private val listen: Int,
    private val upstreamHost: String,
    private val upstreamPort: Int,
    tls: TlsFiles?,
    private val limits: Limits,
    private val queue: LogQueue,
    private val listenHost: String = "0.0.0.0",
    private val newServerSocket: () -> ServerSocket = ::ServerSocket,
) {
    private val ssl: SSLContext? = tls?.let(Relay::serverContext)
    private val ids = AtomicLong()
    private val acceptReported = AtomicBoolean()
    private val connections = ConcurrentHashMap.newKeySet<Connection>()

    @Volatile
    private var server: ServerSocket? = null
    private var acceptor: Thread? = null

    /** The port it listens on, once started (useful when [listen] is 0). */
    val port: Int get() = server?.localPort ?: error("proxy not started")

    fun start() {
        val socket =
            newServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(listenHost, listen))
            }
        server = socket
        acceptor =
            thread(isDaemon = true, name = "myproxy-accept-$service") {
                while (!socket.isClosed) {
                    val client =
                        try {
                            socket.accept()
                        } catch (e: IOException) {
                            if (socket.isClosed) break
                            // Transient (e.g. out of file descriptors): keep listening rather than look healthy but dead.
                            if (acceptReported.compareAndSet(false, true)) {
                                System.err.println("gantry: query-log proxy for $service failed to accept a connection: $e")
                            }
                            Thread.sleep(ACCEPT_RETRY_MS)
                            continue
                        }
                    val connection = Connection(client, ids.incrementAndGet())
                    connections += connection
                    connection.thread = thread(isDaemon = true, name = "myproxy-${connection.id}") { connection.run() }
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
        private var clientTls: SSLSocket? = null

        @Volatile
        private var upstream: Socket? = null

        @Volatile
        private var upstreamTls: SSLSocket? = null

        fun run() {
            try {
                raw.tcpNoDelay = true
                raw.soTimeout = HANDSHAKE_TIMEOUT_MS
                val db =
                    try {
                        connectUpstream().also { upstream = it }
                    } catch (e: IOException) {
                        raw.getOutputStream().write(
                            refusal("gantry query-log proxy cannot reach the database at $upstreamHost:$upstreamPort: ${e.message}"),
                        )
                        return
                    }
                db.soTimeout = HANDSHAKE_TIMEOUT_MS
                relay(db)
            } catch (_: Exception) {
                // Either side went away or broke the protocol; nothing more to relay.
            } finally {
                closeSockets()
                connections -= this
            }
        }

        private fun relay(db: Socket) {
            val session = MySession(service, id, limits, queue::offer)
            try {
                val greeting = readPacket(db.getInputStream()) ?: return
                val tls = ssl != null && MyHandshake.greetingCapabilities(greeting.payload) and Caps.SSL != 0L
                val shown = MyHandshake.rewriteGreeting(greeting.payload, tls)
                Relay.guarded { session.fromServer(MyPacket(greeting.seq, shown)) }
                raw.getOutputStream().apply {
                    write(MyPackets.encode(greeting.seq, shown))
                    flush()
                }

                val first = readPacket(raw.getInputStream()) ?: return
                Relay.guarded { session.fromClient(first) }
                db.getOutputStream().apply {
                    write(MyPackets.encode(first.seq, first.payload))
                    flush()
                }
                var client: Socket = raw
                var server: Socket = db
                if (tls && MyHandshake.isSslRequest(first.payload)) {
                    server = secureUpstream(db)
                    client = secureClient(ssl)
                }
                raw.soTimeout = 0
                db.soTimeout = 0
                pumps(client, server, session)
            } finally {
                Relay.guarded { session.close() }
            }
        }

        private fun pumps(
            client: Socket,
            db: Socket,
            session: MySession,
        ) {
            val clientPackets = MyPacketReader()
            val serverPackets = MyPacketReader()
            val back =
                thread(isDaemon = true, name = "myproxy-$id-server") {
                    try {
                        Relay.pump(db.getInputStream(), client.getOutputStream(), observeFirst = false) { b, n ->
                            serverPackets.feed(b, 0, n).forEach(session::fromServer)
                        }
                    } finally {
                        closeSockets()
                    }
                }
            try {
                // Client packets are observed before they go upstream, so a fast reply always finds its request queued.
                Relay.pump(client.getInputStream(), db.getOutputStream(), observeFirst = true) { b, n ->
                    clientPackets.feed(b, 0, n).forEach(session::fromClient)
                }
            } finally {
                closeSockets()
                back.join(JOIN_MS)
            }
        }

        private fun secureClient(context: SSLContext): SSLSocket {
            val socket = context.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
            clientTls = socket
            socket.useClientMode = false
            socket.startHandshake()
            return socket
        }

        private fun secureUpstream(db: Socket): SSLSocket {
            val socket = trustAll.socketFactory.createSocket(db, upstreamHost, upstreamPort, true) as SSLSocket
            upstreamTls = socket
            socket.useClientMode = true
            socket.startHandshake()
            return socket
        }

        fun closeSockets() {
            runCatching { raw.close() }
            runCatching { clientTls?.close() }
            runCatching { upstream?.close() }
            runCatching { upstreamTls?.close() }
        }
    }

    private fun connectUpstream(): Socket =
        Socket().apply {
            connect(InetSocketAddress(upstreamHost, upstreamPort), CONNECT_TIMEOUT_MS)
            tcpNoDelay = true
        }

    private companion object {
        const val MAX_HANDSHAKE = 1024 * 1024
        const val HANDSHAKE_TIMEOUT_MS = 30_000
        const val CONNECT_TIMEOUT_MS = 10_000
        const val JOIN_MS = 10_000L
        const val ACCEPT_RETRY_MS = 100L
        const val ER_UNKNOWN_ERROR = 1105

        // MySQL inside the app's network presents the image's self-signed certificate.
        val trustAll: SSLContext by lazy {
            val trust =
                object : X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?,
                    ) = Unit

                    override fun checkServerTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?,
                    ) = Unit

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        }

        /** One whole connection-phase packet, or null when the stream ends or it is absurdly large. */
        fun readPacket(input: InputStream): MyPacket? {
            val data = DataInputStream(input)
            val header = ByteArray(4)
            try {
                data.readFully(header)
            } catch (_: IOException) {
                return null
            }
            val length = (header[0].toInt() and 0xFF) or (header[1].toInt() and 0xFF shl 8) or (header[2].toInt() and 0xFF shl 16)
            if (length > MAX_HANDSHAKE) return null
            return MyPacket(header[3].toInt() and 0xFF, ByteArray(length).also(data::readFully))
        }

        // A server's ERR in place of the greeting: no SQLSTATE before the handshake.
        fun refusal(message: String): ByteArray =
            MyPackets.encode(
                0,
                MyPayload()
                    .byte(0xFF)
                    .int16(ER_UNKNOWN_ERROR)
                    .string(message)
                    .toByteArray(),
            )
    }
}
