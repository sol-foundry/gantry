package dev.gantry.gateway

import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** An open WebSocket connection; [userId] is its handshake's `x-user-id` header, when the gateway set one. */
@Serializable
data class SocketConnection(
    val connectionId: String,
    val userId: String? = null,
    val since: String? = null,
    val lastActiveAt: String? = null,
)

/**
 * One event: `connect`, `rejected` (by `$connect`, with its [status]), `message` (to [route], answered [status]),
 * `post` ([bytes] pushed through `@connections`), `gone` (a post to a closed connection), `disconnect` ([reason]) or
 * `$disconnect` (the integration's [status]).
 */
@Serializable
data class SocketEvent(
    val time: String,
    val type: String,
    val connectionId: String? = null,
    val userId: String? = null,
    val path: String? = null,
    val status: Int? = null,
    val route: String? = null,
    val bytes: Int? = null,
    val reason: String? = null,
)

/** What the gateway's WebSocket API is doing; [running] is false when it does not answer. */
@Serializable
data class SocketStatus(
    val running: Boolean,
    val connections: List<SocketConnection> = emptyList(),
    /** Newest first, at most the relay's last 500. */
    val events: List<SocketEvent> = emptyList(),
)

private val relayJson = Json { ignoreUnknownKeys = true }
private val relayClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

/** The open connections and recent events of [app]'s WebSocket relay, read from its management port. */
fun Engine.socketStatus(app: String): SocketStatus {
    val state = loadApp(app).service(GATEWAY) ?: throw GantryException("'$app' has no gateway (run `gantry add $app gateway`)")
    val port = state.ports["websocket"] ?: return SocketStatus(running = false)
    val base = "http://127.0.0.1:$port"
    return try {
        SocketStatus(
            running = true,
            connections = relayJson.decodeFromString(ListSerializer(SocketConnection.serializer()), get("$base/connections")),
            events = relayJson.decodeFromString(ListSerializer(SocketEvent.serializer()), get("$base/events")).asReversed(),
        )
    } catch (_: IOException) {
        SocketStatus(running = false)
    } catch (_: SerializationException) {
        SocketStatus(running = false)
    }
}

private fun get(url: String): String {
    val request =
        HttpRequest
            .newBuilder(URI(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build()
    val response = relayClient.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) throw IOException("relay answered ${response.statusCode()}")
    return response.body()
}
