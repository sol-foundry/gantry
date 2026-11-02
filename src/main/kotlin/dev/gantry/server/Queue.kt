package dev.gantry.server

import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory

@Serializable
data class QueueSummary(
    val name: String,
    val visible: String,
    val inFlight: String,
    val delayed: String,
    val redrive: String,
)

@Serializable
data class QueueMessage(
    val id: String,
    val body: String,
    val receiveCount: Int = 0,
)

/** Inspects the SQS-compatible queue service (ElasticMQ) of an app through the SQS query API. */
class QueueExplorer(
    private val engine: Engine,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun queues(app: String): List<QueueSummary> =
        call(app, null, "ListQueues").texts("QueueUrl").map { it.substringAfterLast('/') }.sorted().map { name ->
            val attributes = attributes(app, name)
            QueueSummary(
                name = name,
                visible = attributes["ApproximateNumberOfMessages"] ?: "0",
                inFlight = attributes["ApproximateNumberOfMessagesNotVisible"] ?: "0",
                delayed = attributes["ApproximateNumberOfMessagesDelayed"] ?: "0",
                redrive = attributes["RedrivePolicy"] ?: "",
            )
        }

    /**
     * Reads up to 10 messages with a zero visibility timeout, so consumers still see them. SQS has no true
     * peek: each read counts as a receive, so on a queue with a redrive policy it spends the message's
     * maxReceiveCount budget. [QueueMessage.receiveCount] reports the count after this read.
     */
    fun peek(
        app: String,
        queue: String,
    ): List<QueueMessage> =
        call(
            app,
            queue,
            "ReceiveMessage",
            "MaxNumberOfMessages" to "10",
            "VisibilityTimeout" to "0",
            "AttributeName.1" to "ApproximateReceiveCount",
        ).elements("Message")
            .map { message ->
                val attributes = message.elements("Attribute").associate { it.text("Name") to it.text("Value") }
                QueueMessage(message.text("MessageId"), message.text("Body"), attributes["ApproximateReceiveCount"]?.toIntOrNull() ?: 0)
            }

    fun send(
        app: String,
        queue: String,
        body: String,
    ) {
        call(app, queue, "SendMessage", "MessageBody" to body)
    }

    fun purge(
        app: String,
        queue: String,
    ) {
        call(app, queue, "PurgeQueue")
    }

    /**
     * Moves every message in [source]'s dead-letter queue back to [source]. Each message is sent before it is
     * deleted, so a failure part-way leaves a duplicate rather than losing one. Bodies and message attributes
     * carry over; the receive count starts again at zero. Returns how many messages moved.
     */
    fun redrive(
        app: String,
        source: String,
    ): Int {
        val policy = attributes(app, source)["RedrivePolicy"] ?: throw GantryException("$source has no dead-letter queue")
        val deadLetterQueue =
            Json
                .parseToJsonElement(policy)
                .jsonObject["deadLetterTargetArn"]
                ?.jsonPrimitive
                ?.content
                ?.substringAfterLast(':') ?: throw GantryException("$source has no dead-letter queue")
        var moved = 0
        while (moved < MAX_REDRIVE) {
            // Received messages stay hidden for the visibility timeout, so the loop ends once each has been seen.
            val batch =
                call(
                    app,
                    deadLetterQueue,
                    "ReceiveMessage",
                    "MaxNumberOfMessages" to "10",
                    "VisibilityTimeout" to "60",
                    "MessageAttributeName.1" to "All",
                ).elements("Message")
            if (batch.isEmpty()) break
            for (message in batch) {
                val attributes =
                    message.elements("MessageAttribute").flatMapIndexed { i, attribute ->
                        val prefix = "MessageAttribute.${i + 1}"
                        val type = attribute.text("DataType")
                        val value =
                            if (type.startsWith("Binary")) {
                                "BinaryValue" to attribute.text("BinaryValue")
                            } else {
                                "StringValue" to
                                    attribute.text("StringValue")
                            }
                        listOf(
                            "$prefix.Name" to attribute.text("Name"),
                            "$prefix.Value.DataType" to type,
                            "$prefix.Value.${value.first}" to value.second,
                        )
                    }
                call(app, source, "SendMessage", "MessageBody" to message.text("Body"), *attributes.toTypedArray())
                call(app, deadLetterQueue, "DeleteMessage", "ReceiptHandle" to message.text("ReceiptHandle"))
                moved++
            }
        }
        return moved
    }

    private fun attributes(
        app: String,
        queue: String,
    ): Map<String, String> =
        call(app, queue, "GetQueueAttributes", "AttributeName.1" to "All")
            .elements("Attribute")
            .associate { it.text("Name") to it.text("Value") }

    private fun call(
        app: String,
        queue: String?,
        action: String,
        vararg params: Pair<String, String>,
    ): Document {
        val appState = engine.loadApp(app)
        val port = appState.service("elasticmq")?.ports?.get("main") ?: throw GantryException("app has no queue service")
        val path = if (queue == null) "/" else "/queue/" + URLEncoder.encode(queue, Charsets.UTF_8).replace("+", "%20")
        val query = (listOf("Action" to action) + params).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path?$query"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
        val response =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                throw GantryException("queue service unreachable (is the app up?): ${e.message}")
            }
        if (response.statusCode() >= 300) throw GantryException("queue service: ${response.body().trim()}")
        return parse(response.body())
    }

    private fun parse(xml: String): Document =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))

    private fun Document.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Document.texts(tag: String): List<String> = elements(tag).map { it.textContent }

    private fun Element.text(tag: String): String = getElementsByTagName(tag).item(0)?.textContent ?: ""

    private companion object {
        /** Stops a redrive that keeps finding messages, e.g. while a producer is still dead-lettering. */
        const val MAX_REDRIVE = 10_000
    }
}
