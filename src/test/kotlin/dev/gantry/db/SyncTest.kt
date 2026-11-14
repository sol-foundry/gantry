package dev.gantry.db

import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import dev.gantry.engine.NO_DOCKER
import dev.gantry.engine.SourceSettings
import dev.gantry.engine.TEST_RUNTIME_LIB
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    private val engine =
        Engine(home = Files.createTempDirectory("gantry-sync-test"), dockerCommand = NO_DOCKER, runtimeLib = TEST_RUNTIME_LIB)
    private val stage = SourceSettings("stage-db.internal", 5432, "shop", "me")

    private fun target(
        service: String,
        db: String,
        user: String,
    ) = DatabaseTarget("shop", service, Dialect.forService(service), "jdbc:unused", db, user, "local-pw", Path.of("/tmp/m"), false)

    @Test
    fun `localhost sources are rewritten to the host gateway`() {
        assertEquals("host.docker.internal", containerHost("localhost"))
        assertEquals("host.docker.internal", containerHost("127.0.0.1"))
        assertEquals("host.docker.internal", containerHost("::1"))
        assertEquals("stage-db.internal", containerHost("stage-db.internal"))
    }

    @Test
    fun `postgres copy clears then restores without owners`() {
        val args =
            copyScript(
                Dialect.Postgres,
                SourceSettings("stage", 5432, "shop", "me"),
                target("postgres", db = "shop", user = "shop"),
                "gantry-shop_default",
            )
        val script = args.last()
        assertTrue("--add-host=host.docker.internal:host-gateway" in args && "--network=gantry-shop_default" in args)
        assertTrue("dropdb" in script && "--force" in script && "createdb" in script)
        assertTrue(
            "pg_dump" in script && "-Fc" in script && "pg_restore" in script && "--no-owner" in script && "--no-privileges" in script,
        )
        assertFalse(script.contains("PGPASSWORD=")) // passwords go in via -e env, not the script text
        assertTrue(script.indexOf("dropdb") < script.indexOf("pg_dump"))
    }

    @Test
    fun `mysql copy recreates the database and strips definers`() {
        val args =
            copyScript(
                Dialect.MySql,
                SourceSettings("localhost", 3306, "shop", "me"),
                target("mysql", db = "shop", user = "shop"),
                "gantry-shop_default",
            )
        val script = args.last()
        assertTrue("--network=gantry-shop_default" in args)
        assertContains(script, "DROP DATABASE IF EXISTS `shop`")
        assertContains(script, "CREATE DATABASE `shop`")
        assertContains(script, "GRANT ALL ON `shop`.* TO ''shop''@''%''".replace("''", "'\\''"))
        listOf(
            "mysqldump",
            "--single-transaction",
            "--routines",
            "--triggers",
            "--set-gtid-purged=OFF",
        ).forEach { assertContains(script, it) }
        assertContains(script, "DEFINER")
        assertContains(script, "host.docker.internal")
        assertFalse(script.contains("local-pw"))
    }

    @Test
    fun `source values are shell quoted`() {
        val args = copyScript(Dialect.Postgres, SourceSettings("h", 5432, "x'; rm -rf / #", "me"), target("postgres", "shop", "shop"), "n")
        assertContains(args.last(), "'x'\\''; rm -rf / #'")
    }

    @Test
    fun `auth failure is reported as AUTH and forgets the saved password`() {
        engine.create("shop", services = listOf("postgres", "mysql"))
        engine.secrets.setPassword("shop", "postgres", "old")
        engine.secrets.setPassword("shop", "mysql", "old")
        val postgres = Sync(engine, connect = { _, _, _ -> throw SQLException("password authentication failed", "28P01") })
        assertEquals(SourceCheck.AUTH, postgres.check("shop", "postgres", stage, "old").result)
        assertNull(engine.secrets.password("shop", "postgres"))

        val mysql = Sync(engine, connect = { _, _, _ -> throw SQLException("Access denied", "28000", 1045) })
        assertEquals(SourceCheck.AUTH, mysql.check("shop", "mysql", stage, "old").result)
        assertNull(engine.secrets.password("shop", "mysql"))
    }

    @Test
    fun `a newly typed password that fails keeps the saved one`() {
        engine.create("shop", services = listOf("postgres"))
        engine.secrets.setPassword("shop", "postgres", "saved")
        val sync = Sync(engine, connect = { _, _, _ -> throw SQLException("password authentication failed", "28P01") })

        assertEquals(SourceCheck.AUTH, sync.check("shop", "postgres", stage, "typo").result)

        assertEquals("saved", engine.secrets.password("shop", "postgres"))
    }

    @Test
    fun `a sync to a new host saves the password it used`() {
        engine.create("shop", services = listOf("postgres"))
        engine.setSource("shop", "postgres", stage)
        engine.secrets.setPassword("shop", "postgres", "stage-pw")
        val sync = Sync(engine, connect = { _, _, _ -> fakeConnection(16, 4) }, containers = FakeContainers { _, _, _ -> 0 })

        val job = sync.runBlocking("shop", "postgres", stage.copy(host = "other-db.internal"), "other-pw") {}

        assertEquals("succeeded", job.state, job.message)
        assertEquals("other-pw", engine.secrets.password("shop", "postgres"))
    }

    @Test
    fun `unreachable sources suggest the vpn and other errors carry the database message`() {
        engine.create("shop", services = listOf("postgres"))
        val down = Sync(engine, connect = { _, _, _ -> throw SQLException("Connection refused", "08001") })
        val probe = down.check("shop", "postgres", stage, "pw")
        assertEquals(SourceCheck.UNREACHABLE, probe.result)
        assertContains(probe.message, "VPN")

        val missing = Sync(engine, connect = { _, _, _ -> throw SQLException("database \"shop\" does not exist", "3D000") })
        assertContains(assertFailsWith<GantryException> { missing.check("shop", "postgres", stage, "pw") }.message!!, "does not exist")
    }

    @Test
    fun `a crafted source host never reaches the driver`() {
        engine.create("shop", services = listOf("mysql"))
        var connected = false
        val sync = Sync(engine, connect = { _, _, _ -> fakeConnection(8, 0).also { connected = true } })
        val evil = SourceSettings("stage.example/x?allowLoadLocalInfile=true&autoDeserialize=true", 3306, "shop", "me")

        assertFailsWith<GantryException> { sync.check("shop", "mysql", evil, "pw") }
        assertFailsWith<GantryException> { copyScript(Dialect.MySql, evil, target("mysql", "shop", "app"), "net") }
        assertFalse(connected)
    }

    @Test
    fun `mysql source urls switch off local files and deserialization`() {
        engine.create("shop", services = listOf("mysql"))
        var url = ""
        Sync(engine, connect = { u, _, _ -> fakeConnection(8, 0).also { url = u } })
            .check("shop", "mysql", SourceSettings("stage-db.internal", 3306, "shop", "me"), "pw")

        listOf("allowLoadLocalInfile=false", "allowUrlInLocalInfile=false", "autoDeserialize=false").forEach { assertContains(url, it) }
    }

    @Test
    fun `a successful check reports the server version`() {
        engine.create("shop", services = listOf("postgres"))
        val probe = Sync(engine, connect = { _, _, _ -> fakeConnection(17, 2) }).check("shop", "postgres", stage, "pw")
        assertEquals(SourceCheck.OK, probe.result)
        assertEquals("17.2", probe.version)
    }

    @Test
    fun `client images are new enough for both servers`() {
        assertEquals("postgres:17-alpine", clientImage(Dialect.Postgres, "17.2", "16"))
        assertEquals("postgres:16-alpine", clientImage(Dialect.Postgres, "14.9", "16"))
        assertEquals("mysql:8.0", clientImage(Dialect.MySql, "8.0", "8.4"))
        assertEquals("mysql:8.4", clientImage(Dialect.MySql, null, "8.4"))
    }

    @Test
    fun `a second sync of the same database is refused`() {
        engine.create("shop", services = listOf("postgres"))
        val release = CountDownLatch(1)
        val containers =
            FakeContainers { _, _, onLine ->
                onLine("gantry-phase: copying")
                onLine("restoring with pw secret-pw")
                release.await(10, TimeUnit.SECONDS)
                0
            }
        val sync = Sync(engine, connect = { _, _, _ -> fakeConnection(16, 4) }, containers = containers)

        val first = sync.start("shop", "postgres", stage, "secret-pw")
        assertEquals("running", first.state)
        val refused = assertFailsWith<GantryException> { sync.start("shop", "postgres", stage, "secret-pw") }
        assertContains(refused.message!!, "already syncing")

        release.countDown()
        val done = awaitFinished(sync)
        assertEquals("succeeded", done.state, done.message)
        assertEquals("done", done.phase)
        assertFalse(done.log.any { "secret-pw" in it })
        assertEquals("secret-pw", engine.secrets.password("shop", "postgres"))
        assertEquals(stage, engine.loadApp("shop").service("postgres")!!.source)
        // Passwords travel in the environment only.
        assertFalse(containers.args.any { "secret-pw" in it })
        assertEquals("secret-pw", containers.env["SRC_PW"])
        assertEquals("postgres:16-alpine", containers.args[containers.args.size - 4])
        // Once finished, another sync may start.
        sync.start("shop", "postgres", stage, "secret-pw")
        awaitFinished(sync)
    }

    @Test
    fun `table names are checked and default to the public schema`() {
        assertEquals(
            listOf(TableRef("public", "users"), TableRef("config", "flags")),
            parseTables(Dialect.Postgres, listOf("users", "config.flags")),
        )
        assertEquals(listOf(TableRef(null, "orders")), parseTables(Dialect.MySql, listOf("orders")))
        listOf("users;drop", "a.b.c", "", "bad name", "-t").forEach { bad ->
            assertFailsWith<GantryException>("rejects '$bad'") { parseTables(Dialect.Postgres, listOf(bad)) }
        }
        assertFailsWith<GantryException> { parseTables(Dialect.MySql, listOf("shop.orders")) }
        assertFailsWith<GantryException> { parseTables(Dialect.Postgres, emptyList()) }
    }

    @Test
    fun `postgres table copy replaces only those rows, in one transaction, without fk checks`() {
        val script =
            tableCopyScript(
                Dialect.Postgres,
                stage,
                target("postgres", "shop", "app"),
                listOf(TableRef("public", "users")),
                "net",
            ).last()

        assertContains(script, "--data-only")
        assertContains(script, "--strict-names")
        assertContains(script, "-t '\"public\".\"users\"'")
        assertContains(script, "SET session_replication_role = replica;")
        assertContains(script, "DELETE FROM \"public\".\"users\";")
        assertContains(script, "--single-transaction")
        assertContains(script, "ON_ERROR_STOP=1")
        assertContains(script, "> /tmp/table.sql")
        assertFalse("dropdb" in script || "createdb" in script || "TRUNCATE" in script)
    }

    @Test
    fun `mysql table copy replaces only those rows, in one transaction, without fk checks`() {
        val script = tableCopyScript(Dialect.MySql, stage, target("mysql", "shop", "app"), listOf(TableRef(null, "orders")), "net").last()

        assertContains(script, "--no-create-info")
        assertContains(script, "SET FOREIGN_KEY_CHECKS=0;")
        assertContains(script, "START TRANSACTION;")
        assertContains(script, "DELETE FROM `orders`;")
        assertContains(script, "COMMIT;")
        assertContains(script, "--skip-lock-tables")
        assertContains(script, "> /tmp/table.sql")
        assertFalse("DROP DATABASE" in script)
    }

    @Test
    fun `a table sync reports its tables and never marks the database incomplete`() {
        engine.create("shop", services = listOf("postgres"))
        val containers =
            FakeContainers { _, _, onLine ->
                onLine("gantry-table-done: public.users 908")
                0
            }
        val sync = Sync(engine, connect = { _, _, _ -> fakeConnection(16, 4) }, containers = containers)

        val job = sync.runBlocking("shop", "postgres", stage, "pw", tables = listOf("users")) {}

        assertEquals("succeeded", job.state, job.message)
        assertEquals(listOf("public.users"), job.scope)
        assertContains(job.message!!, "public.users (908 rows)")
        assertFalse(job.incomplete)
        assertFalse(Files.exists(engine.appDir("shop").resolve(".sync-postgres.incomplete")))
        assertContains(containers.args.last(), "DELETE FROM \"public\".\"users\"")
    }

    @Test
    fun `a failed table sync says local data was left unchanged`() {
        engine.create("shop", services = listOf("postgres"))
        val sync = Sync(engine, connect = { _, _, _ -> fakeConnection(16, 4) }, containers = FakeContainers { _, _, _ -> 1 })

        val job = sync.runBlocking("shop", "postgres", stage, "pw", tables = listOf("users")) {}

        assertEquals("failed", job.state)
        assertContains(job.message!!, "unchanged")
        assertFalse(job.incomplete)
        assertFalse(Files.exists(engine.appDir("shop").resolve(".sync-postgres.incomplete")))
    }

    @Test
    fun `phases run in order`() {
        engine.create("shop", services = listOf("postgres"))
        val phases = mutableListOf<String>()
        lateinit var sync: Sync
        val containers =
            FakeContainers { _, _, onLine ->
                phases += sync.job("shop", "postgres")!!.phase
                onLine("gantry-phase: copying")
                phases += sync.job("shop", "postgres")!!.phase
                0
            }
        sync = Sync(engine, connect = { _, _, _ -> fakeConnection(16, 4) }, containers = containers)
        val job = sync.runBlocking("shop", "postgres", stage, "pw") {}
        assertEquals(listOf("clearing local data", "copying"), phases)
        assertEquals("done", job.phase)
        assertEquals(listOf("shop/postgres"), containers.upped)
    }

    @Test
    fun `a failed copy says the local database is incomplete`() {
        engine.create("shop", services = listOf("postgres"))
        val containers =
            FakeContainers { _, _, onLine ->
                onLine("gantry-phase: copying")
                onLine("pg_dump: error: connection lost")
                1
            }
        val job =
            Sync(engine, connect = {
                _,
                _,
                _,
                ->
                fakeConnection(16, 4)
            }, containers = containers).runBlocking("shop", "postgres", stage, "pw") {}
        assertEquals("failed", job.state)
        assertTrue(job.message!!.startsWith("Copy failed; the local database shop is incomplete. Retry the sync or destroy the app."))
        assertContains(job.message, "pg_dump: error: connection lost")
        assertNull(engine.secrets.password("shop", "postgres"))
    }

    @Test
    fun `an incomplete marker is written before clearing and removed on success`() {
        engine.create("shop", services = listOf("postgres"))
        val marker = engine.appDir("shop").resolve(".sync-postgres.incomplete")
        var presentWhileCopying = false
        val containers =
            FakeContainers { _, _, _ ->
                presentWhileCopying = Files.exists(marker)
                0
            }
        val job =
            Sync(engine, connect = {
                _,
                _,
                _,
                ->
                fakeConnection(16, 4)
            }, containers = containers).runBlocking("shop", "postgres", stage, "pw") {}

        assertEquals("succeeded", job.state, job.message)
        assertTrue(presentWhileCopying)
        assertFalse(Files.exists(marker))
        // A copy orphaned by a killed gantry is removed by name before a new one starts.
        assertEquals(listOf("gantry-sync-shop-postgres"), containers.removed)
        assertTrue("--name=gantry-sync-shop-postgres" in containers.args)
    }

    @Test
    fun `a failed copy keeps the marker and a later process reports the interrupted sync`() {
        engine.create("shop", services = listOf("postgres"))
        val failing = FakeContainers { _, _, _ -> 1 }
        val first = Sync(engine, connect = { _, _, _ -> fakeConnection(16, 4) }, containers = failing)
        val failed = first.runBlocking("shop", "postgres", stage, "pw") {}
        assertTrue(Files.exists(engine.appDir("shop").resolve(".sync-postgres.incomplete")))

        // A restarted dashboard or CLI has no job in memory, only the marker.
        val job = Sync(engine, containers = failing).job("shop", "postgres")!!

        assertEquals("failed", job.state)
        assertEquals(INTERRUPTED_PHASE, job.phase)
        assertEquals(failed.id, job.id)
        assertTrue(job.incomplete)
        assertEquals(
            "A previous sync was interrupted; the local database shop is incomplete. Retry the sync or destroy the app.",
            job.message,
        )
    }

    @Test
    fun `a copy that succeeded is not called incomplete when saving the source fails`() {
        engine.create("shop", services = listOf("postgres"))
        // A directory where the secret store expects its file makes every read of it fail.
        Files.createDirectories(engine.home.resolve("secrets.json"))
        val job =
            Sync(engine, connect = { _, _, _ -> fakeConnection(16, 4) }, containers = FakeContainers { _, _, _ -> 0 })
                .runBlocking("shop", "postgres", stage, "pw") {}

        assertEquals("succeeded", job.state, job.message)
        assertContains(job.message!!, "Copied, but saving the source settings failed")
        assertFalse(Files.exists(engine.appDir("shop").resolve(".sync-postgres.incomplete")))
    }

    @Test
    fun `a later attempt that fails before clearing still reports the half copy`() {
        engine.create("shop", services = listOf("postgres"))
        var auth = false
        val sync =
            Sync(engine, connect = { _, _, _ ->
                if (auth) throw SQLException("bad", "28P01") else fakeConnection(16, 4)
            }, containers = FakeContainers { _, _, _ -> 1 })
        sync.runBlocking("shop", "postgres", stage, "pw") {}
        assertTrue(sync.job("shop", "postgres")!!.incomplete)

        auth = true
        val retry = sync.runBlocking("shop", "postgres", stage, "typo") {}
        assertEquals("checking source", retry.phase)

        val job = sync.job("shop", "postgres")!!
        assertEquals(retry.id, job.id)
        assertTrue(job.incomplete)
    }

    @Test
    fun `no marker and no job means no sync to report`() {
        engine.create("shop", services = listOf("postgres"))
        assertNull(Sync(engine).job("shop", "postgres"))
    }

    @Test
    fun `a failed check leaves local data alone`() {
        engine.create("shop", services = listOf("postgres"))
        val containers = FakeContainers { _, _, _ -> error("must not run") }
        val sync = Sync(engine, connect = { _, _, _ -> throw SQLException("bad", "28P01") }, containers = containers)
        val job = sync.runBlocking("shop", "postgres", stage, "pw") {}
        assertEquals("failed", job.state)
        assertEquals("checking source", job.phase)
        assertFalse(job.message!!.contains("incomplete"))
    }

    @Test
    fun `a source newer than the local postgres is refused before clearing`() {
        engine.create("shop", services = listOf("postgres"))
        val containers = FakeContainers { _, _, _ -> error("must not run") }
        val job =
            Sync(engine, connect = {
                _,
                _,
                _,
                ->
                fakeConnection(17, 2)
            }, containers = containers).runBlocking("shop", "postgres", stage, "pw") {}
        assertEquals("failed", job.state)
        assertEquals("checking source", job.phase)
        assertEquals(
            "Source Postgres 17 is newer than local Postgres 16; set the postgres version option to 17 (or newer) and retry.",
            job.message,
        )
        assertTrue(containers.args.isEmpty())
    }

    @Test
    fun `only strictly newer sources are refused`() {
        assertNull(newerSource(Dialect.Postgres, "16.9", "16"))
        assertNull(newerSource(Dialect.Postgres, "14.2", "16"))
        assertNull(newerSource(Dialect.MySql, "8.0", "8.4"))
        assertNull(newerSource(Dialect.Postgres, "17.0", "latest"))
        assertEquals(
            "Source MySQL 9.1 is newer than local MySQL 8.4; set the mysql version option to 9.1 (or newer) and retry.",
            newerSource(Dialect.MySql, "9.1", "8.4"),
        )
    }

    @Test
    fun `a failing listener cannot leave the job running`() {
        engine.create("shop", services = listOf("postgres"))
        val containers =
            FakeContainers { _, _, onLine ->
                onLine("gantry-phase: copying")
                throw AssertionError("container runner crashed")
            }
        val sync = Sync(engine, connect = { _, _, _ -> fakeConnection(16, 4) }, containers = containers)
        sync.start("shop", "postgres", stage, "pw") { throw IllegalStateException("terminal closed") }
        val job = awaitFinished(sync)
        assertEquals("failed", job.state)
        assertTrue(job.message!!.startsWith("Copy failed; the local database shop is incomplete."))
        // The claim was released: another sync may start.
        sync.start("shop", "postgres", stage, "pw")
        awaitFinished(sync)
    }

    @Test
    fun `a failing listener alone does not abort the copy`() {
        engine.create("shop", services = listOf("postgres"))
        val sync =
            Sync(
                engine,
                connect = {
                    _,
                    _,
                    _,
                    ->
                    fakeConnection(16, 4)
                },
                containers =
                    FakeContainers { _, _, onLine ->
                        onLine("x")
                        0
                    },
            )
        val job = sync.runBlocking("shop", "postgres", stage, "pw") { throw IllegalStateException("terminal closed") }
        assertEquals("succeeded", job.state)
    }

    @Test
    fun `runContainer stops the process when the listener fails`() {
        val home = Files.createTempDirectory("gantry-fake-docker")
        val pidFile = home.resolve("pid")
        val fake = home.resolve("docker")
        Files.writeString(fake, "#!/bin/sh\necho $$ > '$pidFile'\nwhile true; do echo line; sleep 0.05; done\n")
        fake.toFile().setExecutable(true)
        val docker = Engine(home = home, dockerCommand = fake.toString(), runtimeLib = TEST_RUNTIME_LIB).docker
        val started = System.currentTimeMillis()
        assertFailsWith<IllegalStateException> { docker.runContainer(listOf("image")) { throw IllegalStateException("listener broke") } }
        assertTrue(System.currentTimeMillis() - started < 5_000)
        val pid = Files.readString(pidFile).trim().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `database names cannot become connection strings or options`() {
        val evil = SourceSettings("stage", 5432, "host=evil.example dbname=x", "me")
        assertFailsWith<GantryException> { copyScript(Dialect.Postgres, evil, target("postgres", "shop", "shop"), "n") }
        assertFailsWith<GantryException> {
            copyScript(Dialect.Postgres, stage.copy(database = "postgresql://evil.example/x"), target("postgres", "shop", "shop"), "n")
        }
        assertFailsWith<GantryException> {
            copyScript(Dialect.MySql, stage.copy(database = "-hevil.example"), target("mysql", "shop", "shop"), "n")
        }
        assertFailsWith<GantryException> { copyScript(Dialect.Postgres, stage, target("postgres", "--maintenance-db=x", "shop"), "n") }

        engine.create("shop", services = listOf("postgres"))
        val containers = FakeContainers { _, _, _ -> error("must not run") }
        val sync = Sync(engine, connect = { _, _, _ -> fakeConnection(16, 4) }, containers = containers)
        assertFailsWith<GantryException> { sync.runBlocking("shop", "postgres", evil, "pw") {} }
        assertNull(sync.job("shop", "postgres"))
    }

    private fun awaitFinished(sync: Sync): SyncJob {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val job = sync.job("shop", "postgres")!!
            if (job.state != "running") return job
            Thread.sleep(20)
        }
        error("sync did not finish")
    }

    private class FakeContainers(
        val body: (List<String>, Map<String, String>, (String) -> Unit) -> Int,
    ) : SyncContainers {
        val upped = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var args: List<String> = emptyList()
        var env: Map<String, String> = emptyMap()

        override fun remove(name: String) {
            removed += name
        }

        override fun upService(
            app: String,
            service: String,
        ) {
            upped += "$app/$service"
        }

        override fun run(
            args: List<String>,
            env: Map<String, String>,
            onLine: (String) -> Unit,
        ): Int {
            this.args = args
            this.env = env
            return body(args, env, onLine)
        }
    }

    private fun fakeConnection(
        major: Int,
        minor: Int,
    ): Connection {
        val meta =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DatabaseMetaData::class.java)) { _, method, _ ->
                when (method.name) {
                    "getDatabaseMajorVersion" -> major
                    "getDatabaseMinorVersion" -> minor
                    "getDatabaseProductVersion" -> "$major.$minor"
                    else -> throw SQLException("not faked: ${method.name}")
                }
            } as DatabaseMetaData
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "getMetaData" -> meta
                "close" -> null
                "isClosed" -> false
                else -> throw SQLException("not faked: ${method.name}")
            }
        } as Connection
    }
}
