package dev.gantry.server

import com.sun.net.httpserver.HttpServer
import dev.gantry.engine.Engine
import dev.gantry.engine.NO_DOCKER
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ServerTest {
    private val gantry = Engine(home = Files.createTempDirectory("gantry-server-test"), dockerCommand = NO_DOCKER)

    private fun dashboard(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { dashboardModule(gantry) }
            block()
        }

    @Test
    fun `guard rejects foreign hosts and origins`() =
        dashboard {
            assertEquals(HttpStatusCode.OK, client.get("/api/apps") { header(HttpHeaders.Host, "localhost:7070") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/apps") { header(HttpHeaders.Host, "evil.example:7070") }.status)
            val crossSite =
                client.get("/api/apps") {
                    header(HttpHeaders.Host, "localhost:7070")
                    header(HttpHeaders.Origin, "http://evil.example")
                }
            assertEquals(HttpStatusCode.Forbidden, crossSite.status)
        }

    @Test
    fun `unknown api paths are 404 not the dashboard page`() =
        dashboard {
            val response = client.get("/api/apps/shop/cache/info")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertContains(response.bodyAsText(), "no such endpoint")
        }

    @Test
    fun `unknown app is a user error`() =
        dashboard {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/apps/nope").status)
        }

    @Test
    fun `env names endpoint renames variables`() =
        dashboard {
            gantry.create("shop", services = listOf("postgres"))

            val response =
                client.put("/api/apps/shop/env-names") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"names":{"DB_HOST":"PG_HOST","DB_PORT":""}}""")
                }

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            assertContains(body, "PG_HOST=localhost")
            assertFalse("DB_PORT=" in body)
        }

    @Test
    fun `queue explorer parses ElasticMQ responses`() =
        dashboard {
            gantry.create("shop", services = listOf("elasticmq"))
            val fake = fakeElasticMq()
            val state = gantry.appDir("shop").resolve("app.json")
            // The allocated port depends on what is free on this machine, so rewrite whatever was picked.
            state.writeText(state.readText().replace(Regex("\"main\": \\d+"), "\"main\": ${fake.address.port}"))

            val queues = client.get("/api/apps/shop/queue/queues").bodyAsText()
            assertContains(queues, "\"name\":\"jobs\"")
            assertContains(queues, "\"visible\":\"3\"")
            assertContains(client.get("/api/apps/shop/queue/jobs/messages").bodyAsText(), "\"body\":\"hello\"")

            fake.stop(0)
        }

    private fun fakeElasticMq(): HttpServer {
        val ns = " xmlns=\"http://queue.amazonaws.com/doc/2012-11-05/\""
        val responses =
            mapOf(
                "ListQueues" to
                    "<ListQueuesResponse$ns><ListQueuesResult><QueueUrl>http://x/000000000000/jobs</QueueUrl></ListQueuesResult></ListQueuesResponse>",
                "GetQueueAttributes" to
                    "<GetQueueAttributesResponse$ns><GetQueueAttributesResult><Attribute><Name>ApproximateNumberOfMessages</Name><Value>3</Value></Attribute></GetQueueAttributesResult></GetQueueAttributesResponse>",
                "ReceiveMessage" to
                    "<ReceiveMessageResponse$ns><ReceiveMessageResult><Message><MessageId>m1</MessageId><Body>hello</Body></Message></ReceiveMessageResult></ReceiveMessageResponse>",
            )
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val action =
                    exchange.requestURI.query
                        .split("&")
                        .first { it.startsWith("Action=") }
                        .removePrefix("Action=")
                val body = (responses[action] ?: "<Response$ns/>").toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }
}
