package dev.gantry.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests must never reach the real daemon: compose project names are global, so `destroy("shop")` would hit a real app. */
const val NO_DOCKER = "gantry-test-no-docker"

class EngineTest {
    private val home: Path = Files.createTempDirectory("gantry-test")
    private val engine = Engine(home = home, dockerCommand = NO_DOCKER)

    private fun env(app: String) = engine.env(engine.loadApp(app)).toMap()

    @Test
    fun `a source host is a plain hostname or IP address`() {
        listOf("stage-db.internal", "localhost", "10.0.0.5", "::1", "[::1]", "fe80::1", "DB-1.example.com").forEach { checkSourceHost(it) }
        listOf(
            "stage.example/x?allowLoadLocalInfile=true",
            "host:5432",
            "a@b",
            "a b",
            "-h",
            "x#y",
            "[::1]x",
            "",
            "a..b",
            "-lead.example",
        ).forEach { bad -> assertFailsWith<GantryException>("rejects '$bad'") { checkSourceHost(bad) } }
    }

    @Test
    fun `a crafted source host is refused when saved`() {
        engine.create("shop", services = listOf("postgres"))

        assertFailsWith<GantryException> {
            engine.setSource("shop", "postgres", SourceSettings("stage.example/db?sslfactory=x", 5432, "shop", "me"))
        }
        assertNull(engine.readApp("shop").service("postgres")!!.source)
    }

    @Test
    fun `services are named by their real names only`() {
        val e = assertFailsWith<GantryException> { engine.create("shop", services = listOf("database")) }

        assertTrue("unknown service 'database'" in e.message!!)
        listOf("postgres", "mysql", "valkey", "elasticmq", "temporal", "kafka").forEach { assertTrue(it in e.message!!, "lists $it") }
    }

    @Test
    fun `readApp drops the legacy cache UI without writing anything`() {
        engine.create("shop", services = listOf("valkey"))
        val appJson = home.resolve("apps/shop/app.json")
        // State saved by an older gantry, where RedisInsight was its own service.
        val saved =
            kotlinx.serialization.json.Json
                .parseToJsonElement(appJson.readText())
                .jsonObject
        val legacy = JsonObject(mapOf("id" to JsonPrimitive("redisinsight")))
        appJson.writeText(JsonObject(saved + ("services" to JsonArray(saved.getValue("services").jsonArray + legacy))).toString())
        val before = appJson.readText()
        val portsBefore = home.resolve("ports.json").readText()

        val app = engine.readApp("shop")

        assertNull(app.service("redisinsight"))
        assertNotNull(app.service("valkey"))
        assertEquals(before, appJson.readText())
        assertEquals(portsBefore, home.resolve("ports.json").readText())
    }

    @Test
    fun `readApp reports a missing app like loadApp`() {
        val e = assertFailsWith<GantryException> { engine.readApp("ghost") }
        assertTrue("gantry create ghost" in e.message!!)
    }

    @Test
    fun `app state is written atomically`() {
        val target = home.resolve("state.json")
        target.writeText("old")

        writeAtomically(target, "new")

        assertEquals("new", target.readText())
        assertEquals(
            listOf("state.json"),
            Files
                .list(home)
                .use { files ->
                    files.map { it.fileName.toString() }.toList()
                }.filter { it.startsWith("state") },
        )
    }

    @Test
    fun `GANTRY_DOCKER overrides the docker command`() {
        assertEquals("/opt/bin/docker", Engine.defaultDockerCommand(mapOf("GANTRY_DOCKER" to "/opt/bin/docker")))
        assertEquals("docker", Engine.defaultDockerCommand(emptyMap()))
        assertEquals("docker", Engine.defaultDockerCommand(mapOf("GANTRY_DOCKER" to " ")))
    }

    @Test
    fun `docker is unreachable when the command does not exist`() {
        assertFalse(engine.docker.reachable())
    }

    @Test
    fun `create resolves aliases and defaults database to the app name`() {
        engine.create("shop", services = listOf("postgres", "valkey"))

        val env = env("shop")
        assertEquals("shop", env["DB_NAME"])
        assertEquals("shop", env["DB_USER"])
        assertFalse("AWS_ENDPOINT_URL_SQS" in env, "env only contains keys for services the app has")
    }

    @Test
    fun `second app gets distinct host ports`() {
        engine.create("shop", services = listOf("postgres"))
        engine.create("billing", services = listOf("postgres"))

        assertNotEquals(env("shop")["DB_PORT"], env("billing")["DB_PORT"])
    }

    @Test
    fun `a removed service gets its ports back when added again`() {
        engine.create("shop", services = listOf("postgres", "elasticmq"))
        val before = env("shop")["AWS_ENDPOINT_URL_SQS"]
        engine.create("other", services = listOf("elasticmq"))

        engine.remove("shop", "elasticmq")
        engine.create("third", services = listOf("elasticmq"))
        engine.add("shop", "elasticmq")

        assertEquals(before, env("shop")["AWS_ENDPOINT_URL_SQS"], "settings written for the old port keep working")
        assertNotEquals(before, env("third")["AWS_ENDPOINT_URL_SQS"], "a reserved port isn't handed to another app")
    }

    @Test
    fun `the cache is also given as host and port in one value`() {
        engine.create("shop", services = listOf("valkey"))

        assertEquals("localhost:${env("shop")["CACHE_PORT"]}", env("shop")["CACHE_ENDPOINT"])
    }

    @Test
    fun `ports stay stable when services are added`() {
        engine.create("shop", services = listOf("postgres"))
        val before = env("shop")["DB_PORT"]

        engine.add("shop", "valkey")

        assertEquals(before, env("shop")["DB_PORT"])
    }

    /** No bundled service requires another today, so these tests make mysql require valkey. */
    private val requiring =
        Engine(
            home = Files.createTempDirectory("gantry-requires-test"),
            catalog = Catalog(Catalog.load().all().map { if (it.id == "mysql") it.copy(requires = listOf("valkey")) else it }),
            dockerCommand = NO_DOCKER,
        )

    @Test
    fun `adding a service pulls in what it requires`() {
        val app = requiring.create("shop", services = listOf("mysql"))

        assertNotNull(app.service("valkey"))
    }

    @Test
    fun `remove refuses while a dependent service exists`() {
        requiring.create("shop", services = listOf("mysql"))

        val error = assertFailsWith<GantryException> { requiring.remove("shop", "valkey") }
        assertTrue("mysql" in error.message!!)
    }

    @Test
    fun `valkey comes with RedisInsight as its UI`() {
        engine.create("shop", services = listOf("valkey"))

        val compose = engine.appDir("shop").resolve("compose.yml").readText()
        assertEquals(listOf("valkey"), engine.loadApp("shop").services.map { it.id })
        assertTrue("redisinsight:" in compose && "redisinsight-init:" in compose && "redisinsight-data:" in compose)
    }

    @Test
    fun `apps that listed RedisInsight as a service are upgraded and keep its port`() {
        engine.create("shop", services = listOf("valkey"))
        val legacy = home.resolve("apps/shop/app.json")
        legacy.writeText(
            legacy.readText().replace(
                Regex("""\]\s*}\s*$"""),
                ", {\"id\": \"redisinsight\", \"ports\": {\"ui\": 5599}}]}",
            ),
        )
        // Older versions gave valkey no ui port; RedisInsight held it under its own id.
        engine.updatePorts {
            it.remove("shop/valkey/ui")
            it["shop/redisinsight/ui"] = 5599
        }

        val app = engine.loadApp("shop")

        assertEquals(listOf("valkey"), app.services.map { it.id })
        assertEquals(5599, app.service("valkey")!!.ports["ui"])
    }

    @Test
    fun `add rejects an unknown option`() {
        engine.create("shop")

        assertFailsWith<GantryException> { engine.add("shop", "elasticmq", mapOf("bogus" to "1")) }
    }

    @Test
    fun `invalid app names are rejected`() {
        listOf("Bad", "1x", "a/b", "../x", "").forEach { name ->
            assertFailsWith<GantryException>("name '$name'") { engine.create(name) }
        }
    }

    @Test
    fun `queue option generates config with dead-letter queues`() {
        engine.create("shop")
        engine.add("shop", "elasticmq", mapOf("queues" to "jobs,slow:3600:slow_dead"))

        val conf = engine.appDir("shop").resolve("elasticmq.conf").readText()
        listOf("jobs {", "name = \"jobs-dlq\"", "jobs-dlq {}", "defaultVisibilityTimeout = 3600 seconds", "name = \"slow_dead\"")
            .forEach { assertTrue(it in conf, "elasticmq.conf missing '$it'") }
    }

    @Test
    fun `kafka advertises its host port and comes with Kafka UI`() {
        engine.create("shop", services = listOf("kafka"))

        val app = engine.loadApp("shop")
        val port = app.service("kafka")!!.ports.getValue("main")
        val compose = engine.appDir("shop").resolve("compose.yml").readText()
        assertEquals("localhost:$port", env("shop")["KAFKA_BOOTSTRAP_SERVERS"])
        assertTrue("EXTERNAL://localhost:$port" in compose)
        assertTrue("kafka-ui:" in compose && "kafka-init:" in compose)
    }

    @Test
    fun `kafka topics option creates topics with their partition counts`() {
        engine.create("shop")
        engine.add("shop", "kafka", mapOf("topics" to "orders:6, payments", "partitions" to "3"))

        val compose = engine.appDir("shop").resolve("compose.yml").readText()
        assertTrue("--topic orders --partitions 6" in compose)
        assertTrue("--topic payments --partitions 3" in compose)
    }

    @Test
    fun `invalid kafka topics are rejected and not saved`() {
        engine.create("shop", services = listOf("kafka"))

        listOf("bad name", "x;rm -rf /", "orders:0", "orders:many").forEach { topics ->
            assertFailsWith<GantryException>(topics) { engine.add("shop", "kafka", mapOf("topics" to topics)) }
        }
        assertFailsWith<GantryException> { engine.add("shop", "kafka", mapOf("partitions" to "0")) }
        assertEquals(emptyMap(), engine.loadApp("shop").service("kafka")!!.options)
    }

    @Test
    fun `workflow option registers search attributes`() {
        engine.create("shop")
        engine.add("shop", "temporal", mapOf("search_attributes" to "UserId=Keyword,Count=Int"))

        val compose = engine.appDir("shop").resolve("compose.yml").readText()
        assertTrue("--search-attribute=UserId=Keyword" in compose)
        assertTrue("--search-attribute=Count=Int" in compose)
    }

    @Test
    fun `fullstack preset builds a valid app`() {
        assertEquals(4, engine.create("shop", preset = "fullstack").services.size)
    }

    @Test
    fun `option values are YAML escaped in the compose file`() {
        engine.create("shop")
        engine.add("shop", "postgres", mapOf("password" to "p\"w\\d"))

        assertTrue("POSTGRES_PASSWORD: \"p\\\"w\\\\d\"" in engine.appDir("shop").resolve("compose.yml").readText())
    }

    @Test
    fun `generated config changes alter the compose definition so up recreates the container`() {
        engine.create("shop", services = listOf("elasticmq"))
        val compose = engine.appDir("shop").resolve("compose.yml")
        val hash = Regex("gantry.config-hash: \"(\\w+)\"")
        val before = hash.find(compose.readText())?.groupValues?.get(1)

        engine.add("shop", "elasticmq", mapOf("queues" to "jobs"))

        val after = hash.find(compose.readText())?.groupValues?.get(1)
        assertNotNull(before)
        assertNotEquals(before, after)
    }

    @Test
    fun `mysql renders with its own env keys and seed directory`() {
        engine.create("shop", services = listOf("mysql", "postgres"))

        val env = env("shop")
        val password =
            engine
                .loadApp("shop")
                .service("mysql")!!
                .options
                .getValue("password")
        assertTrue(env.getValue("MYSQL_URL").startsWith("mysql://shop:$password@localhost:"))
        assertEquals("shop", env["DB_NAME"], "postgres and mysql env keys do not collide")
        val compose = engine.appDir("shop").resolve("compose.yml").readText()
        assertTrue("/initdb-mysql:/docker-entrypoint-initdb.d" in compose)
        assertTrue("mysql-data:" in compose)
        assertTrue(Files.isDirectory(engine.appDir("shop").resolve("migrations/mysql")))
    }

    @Test
    fun `path options are stored absolute`() {
        engine.create("shop", services = listOf("postgres"))

        engine.add("shop", "postgres", mapOf("migrations" to "~/svc/db/migration"))
        val tilde = engine.serviceOptions(engine.loadApp("shop"), "postgres").getValue("migrations")
        engine.add("shop", "postgres", mapOf("migrations" to "rel/dir"))
        val relative = engine.serviceOptions(engine.loadApp("shop"), "postgres").getValue("migrations")

        assertEquals(System.getProperty("user.home") + "/svc/db/migration", tilde)
        assertEquals(Path.of("rel/dir").toAbsolutePath().toString(), relative)
    }

    @Test
    fun `destroy releases ports and files without needing docker`() {
        engine.create("shop", services = listOf("postgres"))

        engine.docker.destroy("shop")

        assertFalse(Files.exists(engine.appDir("shop")))
        assertFalse("shop/" in home.resolve("ports.json").readText())
    }

    @Test
    fun `env names rename and omit variables`() {
        engine.create("shop", services = listOf("postgres", "valkey"))

        engine.setEnvNames("shop", mapOf("DB_HOST" to "POSTGRES_HOST", "REDIS_URL" to ""))

        val env = env("shop")
        assertEquals("localhost", env["POSTGRES_HOST"])
        assertNull(env["DB_HOST"])
        assertNull(env["REDIS_URL"])
        assertNotNull(env["DB_PORT"], "unmapped keys keep their default name")
    }

    @Test
    fun `env names survive adding services and reach the generated env file`() {
        engine.create("shop", services = listOf("postgres"))
        engine.setEnvNames("shop", mapOf("DB_HOST" to "POSTGRES_HOST"))

        engine.add("shop", "valkey")

        assertEquals("localhost", env("shop")["POSTGRES_HOST"])
        assertTrue("POSTGRES_HOST=localhost" in engine.appDir("shop").resolve("shop.env").readText())
    }

    @Test
    fun `env names validation`() {
        engine.create("shop", services = listOf("postgres"))

        mapOf(
            "unknown key" to mapOf("NOPE" to "X"),
            "invalid name" to mapOf("DB_HOST" to "bad-name"),
            "duplicate name" to mapOf("DB_HOST" to "DB_PORT"),
            "duplicate with unrenamed" to mapOf("DB_HOST" to "DATABASE_URL"),
        ).forEach { (label, names) -> assertFailsWith<GantryException>(label) { engine.setEnvNames("shop", names) } }
    }

    @Test
    fun `mapping a key to itself is not stored`() {
        engine.create("shop", services = listOf("postgres"))

        assertTrue(engine.setEnvNames("shop", mapOf("DB_HOST" to "DB_HOST")).envNames.isEmpty())
    }

    @Test
    fun `url filter percent-encodes credentials in DATABASE_URL`() {
        engine.create("shop")
        engine.add("shop", "postgres", mapOf("user" to "app user", "password" to "p@ss/w#rd"))

        val url = env("shop").getValue("DATABASE_URL")
        assertTrue(url.startsWith("postgresql://app%20user:p%40ss%2Fw%23rd@localhost:"), url)
        assertEquals("p@ss/w#rd", env("shop")["DB_PASSWORD"])
    }

    @Test
    fun `a new database gets its own generated password`() {
        engine.create("shop", services = listOf("postgres"))
        engine.add("shop", "mysql")
        val app = engine.loadApp("shop")
        val pg = app.service("postgres")!!.options.getValue("password")
        val mysql = app.service("mysql")!!.options
        assertTrue(pg.length >= 24 && pg != "shop", pg)
        assertTrue(mysql.getValue("password").length >= 24)
        assertTrue(mysql.getValue("root_password").length >= 24)
        assertNotEquals(mysql.getValue("password"), mysql.getValue("root_password"))
        assertTrue(
            engine
                .appDir("shop")
                .resolve("shop.env")
                .readText()
                .contains("DB_PASSWORD=$pg"),
        )
    }

    @Test
    fun `reconfiguring a database keeps its password and a given one is used`() {
        engine.create("shop", services = listOf("postgres"))
        val before =
            engine
                .loadApp("shop")
                .service("postgres")!!
                .options
                .getValue("password")
        engine.add("shop", "postgres", mapOf("version" to "16"))
        assertEquals(
            before,
            engine
                .loadApp("shop")
                .service("postgres")!!
                .options
                .getValue("password"),
        )
        engine.add("shop", "mysql", mapOf("password" to "chosen"))
        assertEquals(
            "chosen",
            engine
                .loadApp("shop")
                .service("mysql")!!
                .options
                .getValue("password"),
        )
    }

    @Test
    fun `a removed database's volume is found and deleted only on request`() {
        val tools: Path = Files.createTempDirectory("gantry-fake-docker")
        val log: Path = tools.resolve("docker.log")
        val docker: Path = tools.resolve("docker")
        Files.writeString(
            docker,
            "#!/bin/sh\necho \"\$@\" >> '$log'\n" +
                "[ \"\$1 \$2\" = \"volume inspect\" ] && [ \"\$3\" != \"gantry-shop_postgres-data\" ] && exit 1\nexit 0\n",
        )
        Files.setPosixFilePermissions(
            docker,
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwx------"),
        )
        val withVolume = Engine(home = tools.resolve("home"), dockerCommand = docker.toString())
        withVolume.create("shop", services = listOf("valkey"))

        assertEquals("gantry-shop_postgres-data", withVolume.leftoverVolume("shop", "postgres"))
        assertNull(withVolume.leftoverVolume("shop", "mysql"))
        assertNull(withVolume.leftoverVolume("shop", "valkey"))

        withVolume.add("shop", "postgres")
        assertFalse(log.readText().contains("volume rm"))
        // Once the database is back in the app, its volume is its own again.
        assertNull(withVolume.leftoverVolume("shop", "postgres"))

        withVolume.remove("shop", "postgres")
        withVolume.add("shop", "postgres", freshData = true)
        val calls = log.readText().lines()
        assertTrue("rm -f shop-postgres" in calls, calls.toString())
        assertTrue("volume rm gantry-shop_postgres-data" in calls, calls.toString())
    }
}

class TemplateTest {
    @Test
    fun `parseQueues applies defaults`() {
        assertEquals(
            listOf(QueueSpec("a", null, "a-dlq"), QueueSpec("b", "300", "b-dlq"), QueueSpec("c", "60", "custom_dlq")),
            parseQueues("a, b:300 ,c:60:custom_dlq,"),
        )
    }

    @Test
    fun `quoteEnvValue quotes only when needed`() {
        assertEquals("plain", quoteEnvValue("plain"))
        assertEquals("'has space'", quoteEnvValue("has space"))
        assertEquals("'p@ss \"x\" \$HOME'", quoteEnvValue("p@ss \"x\" \$HOME"), "single quotes keep the value literal")
        assertEquals("\"it's \\\"\\\$x\\\\\"", quoteEnvValue("it's \"\$x\\"))
        assertEquals("", quoteEnvValue(""))
    }

    @Test
    fun `render rejects an unknown filter`() {
        assertFailsWith<GantryException> { Template.render("{{a|shout}}", mapOf("a" to "x")) }
    }

    @Test
    fun `render fails on an unresolved placeholder`() {
        assertFailsWith<GantryException> { Template.render("{{missing}}", emptyMap()) }
    }
}

class ValkeyPersistenceTest {
    private val engine = Engine(home = Files.createTempDirectory("gantry-persist-test"), dockerCommand = NO_DOCKER)

    private fun compose(app: String) = engine.appDir(app).resolve("compose.yml").readText()

    @Test
    fun `cache is memory-only by default`() {
        engine.create("shop", services = listOf("valkey"))

        val compose = compose("shop")
        assertTrue("tmpfs:" in compose)
        assertFalse("valkey-data" in compose, "no volume should be declared or mounted")
        assertFalse("--appendonly" in compose)
    }

    @Test
    fun `persist stores data in a named volume with append-only logging`() {
        engine.create("shop")
        engine.add("shop", "valkey", mapOf("persist" to "true"))

        val compose = compose("shop")
        assertTrue("valkey-data:/data" in compose)
        assertTrue("\nvolumes:\n  valkey-data:\n" in compose, "volume must be declared at top level")
        assertTrue("--appendonly" in compose)
        assertFalse("tmpfs:" in compose)
    }

    @Test
    fun `turning persist off again drops the mount`() {
        engine.create("shop")
        engine.add("shop", "valkey", mapOf("persist" to "true"))
        engine.add("shop", "valkey", mapOf("persist" to "false"))

        assertFalse("valkey-data" in compose("shop"))
    }

    @Test
    fun `persist only accepts true or false`() {
        engine.create("shop")

        assertFailsWith<GantryException> { engine.add("shop", "valkey", mapOf("persist" to "yes")) }
    }

    @Test
    fun `source settings are stored with the app`() {
        engine.create("shop", services = listOf("postgres"))
        engine.setSource("shop", "postgres", SourceSettings("stage", 5432, "shop", "me"))
        assertEquals(
            "stage",
            engine
                .loadApp("shop")
                .service("postgres")!!
                .source!!
                .host,
        )
    }

    @Test
    fun `changing the source database forgets its saved password`() {
        engine.create("shop", services = listOf("postgres"))
        val stage = SourceSettings("stage", 5432, "shop", "me")
        engine.setSource("shop", "postgres", stage)
        engine.secrets.setPassword("shop", "postgres", "x")

        engine.setSource("shop", "postgres", stage.copy(user = "someone"))
        assertEquals("x", engine.secrets.password("shop", "postgres"))

        engine.setSource("shop", "postgres", stage.copy(host = "prod"))
        assertNull(engine.secrets.password("shop", "postgres"))
    }

    @Test
    fun `clearing the source forgets its saved password`() {
        engine.create("shop", services = listOf("postgres"))
        engine.setSource("shop", "postgres", SourceSettings("stage", 5432, "shop", "me"))
        engine.secrets.setPassword("shop", "postgres", "x")

        engine.setSource("shop", "postgres", null)

        assertNull(engine.secrets.password("shop", "postgres"))
    }

    @Test
    fun `removing a service forgets its saved password`() {
        engine.create("shop", services = listOf("postgres", "mysql"))
        engine.secrets.setPassword("shop", "postgres", "pg")
        engine.secrets.setPassword("shop", "mysql", "my")

        engine.remove("shop", "mysql")

        assertNull(engine.secrets.password("shop", "mysql"))
        assertEquals("pg", engine.secrets.password("shop", "postgres"))
    }

    @Test
    fun `destroy forgets the app's saved passwords`() {
        engine.create("shop", services = listOf("postgres"))
        engine.secrets.setPassword("shop", "postgres", "x")
        engine.docker.destroy("shop")
        assertNull(engine.secrets.password("shop", "postgres"))
    }

    @Test
    fun `database passwords are marked secret in the catalog`() {
        assertTrue(
            engine.catalog
                .resolve("postgres")
                .optionMeta
                .getValue("password")
                .secret,
        )
        assertTrue(
            engine.catalog
                .resolve("mysql")
                .optionMeta
                .getValue("root_password")
                .secret,
        )
    }
}
