package dev.gantry.engine

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DynamoDbTablesTest {
    @Test
    fun `parses keys, typed keys, indexes with projections and a ttl attribute`() {
        val tables = parseTables("sessions:connectionId,topic+topic-index=topic/keys~ttl; counters:id#N")

        assertEquals(2, tables.size)
        val sessions = tables[0]
        assertEquals("sessions", sessions.name)
        assertEquals(KeySpec("connectionId", "S"), sessions.hash)
        assertEquals(KeySpec("topic", "S"), sessions.range)
        assertEquals(listOf(IndexSpec("topic-index", KeySpec("topic", "S"), null, "KEYS_ONLY")), sessions.indexes)
        assertEquals("ttl", sessions.ttl)
        assertEquals(KeySpec("id", "N"), tables[1].hash)
        assertEquals(emptyList(), tables[1].indexes)
    }

    @Test
    fun `an index projects all attributes unless it says keys`() {
        assertEquals(
            "ALL",
            parseTables("items:a+by-b=b,a")
                .single()
                .indexes
                .single()
                .projection,
        )
    }

    @Test
    fun `invalid tables name what is wrong`() {
        listOf(
            "ab:id" to "a table name",
            "users" to "say its keys",
            "users:a,b,c" to "keys are hash[,range]",
            "users:id#X" to "key type",
            "users:id+idx=x/some" to "projection",
            "users:id+idx=id#N" to "two types",
            "users:id~bad name" to "not an attribute name",
        ).forEach { (spec, why) ->
            val e = assertFailsWith<GantryException>(spec) { parseTables(spec) }
            assertContains(e.message!!, why, message = spec)
        }
    }

    @Test
    fun `the init job creates each missing table with its indexes and turns on ttl`() {
        val engine =
            Engine(
                home =
                    java.nio.file.Files
                        .createTempDirectory("gantry-dynamodb"),
                dockerCommand = NO_DOCKER,
            )
        engine.create("shop")
        engine.add("shop", "dynamodb", mapOf("tables" to "sessions:connectionId,topic+topic-index=topic/keys~ttl"))
        val compose =
            engine
                .appDir("shop")
                .resolve("compose.yml")
                .toFile()
                .readText()

        assertContains(compose, "aws dynamodb describe-table --table-name sessions")
        assertContains(compose, "--key-schema AttributeName=connectionId,KeyType=HASH AttributeName=topic,KeyType=RANGE")
        assertContains(compose, "\"IndexName\":\"topic-index\"")
        assertContains(compose, "\"ProjectionType\":\"KEYS_ONLY\"")
        assertContains(compose, "--time-to-live-specification Enabled=true,AttributeName=ttl")
        assertContains(compose, "\"-sharedDb\", \"-inMemory\"")
        val port =
            engine
                .loadApp("shop")
                .service("dynamodb")!!
                .ports
                .getValue("main")
        assertEquals("http://localhost:$port", engine.env(engine.loadApp("shop")).toMap()["AWS_ENDPOINT_URL_DYNAMODB"])
    }

    @Test
    fun `persist keeps the data in a volume and an invalid table is refused on add`() {
        val engine =
            Engine(
                home =
                    java.nio.file.Files
                        .createTempDirectory("gantry-dynamodb"),
                dockerCommand = NO_DOCKER,
            )
        engine.create("shop")
        engine.add("shop", "dynamodb", mapOf("persist" to "true"))
        val compose =
            engine
                .appDir("shop")
                .resolve("compose.yml")
                .toFile()
                .readText()
        assertContains(compose, "dynamodb-data:/home/dynamodblocal/data")
        assertTrue("-inMemory" !in compose)

        assertFailsWith<GantryException> { engine.add("shop", "dynamodb", mapOf("tables" to "x")) }
    }
}
