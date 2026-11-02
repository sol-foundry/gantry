package dev.gantry.db

import dev.gantry.engine.Engine
import dev.gantry.engine.SourceSettings
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Against the real Docker daemon and gantry home, so skipped unless GANTRY_E2E=1. Needs throwaway apps, each up:
 * `sync-src` and `sync-test` with postgres (source seeded with `stock(id, qty)` holding (1, 5)), and for MySQL
 * `sync-src-my` and `sync-test-my` with mysql, seeded the same way.
 */
@EnabledIfEnvironmentVariable(named = "GANTRY_E2E", matches = "1")
class SyncEndToEndTest {
    private val engine = Engine()

    @Test
    fun `postgres copies the source into the local database`() = copies("postgres", "sync-src", "sync-test")

    @Test
    fun `mysql copies the source into the local database`() = copies("mysql", "sync-src-my", "sync-test-my")

    /** Needs a `limited` login role (password `lim-pw-123`) on sync-src that cannot read `stock`. */
    @Test
    fun `a dump that fails partway reports the local database as incomplete`() {
        val port =
            engine
                .loadApp("sync-src")
                .service("postgres")!!
                .ports
                .getValue("main")
        val job =
            Sync(
                engine,
            ).runBlocking("sync-test", "postgres", SourceSettings("localhost", port, "sync_src", "limited"), "lim-pw-123") {
                println("[fail] $it")
            }
        println("[fail] ${job.state}: ${job.message}")
        assertEquals("failed", job.state)
        assertTrue(job.message!!.startsWith("Copy failed; the local database sync_test is incomplete."))
    }

    private fun copies(
        service: String,
        sourceApp: String,
        localApp: String,
    ) {
        val from = DatabaseTarget.resolve(engine, sourceApp, service)
        val port =
            engine
                .loadApp(sourceApp)
                .service(service)!!
                .ports
                .getValue("main")
        val source = SourceSettings("localhost", port, from.database, from.user)
        val sync = Sync(engine)

        assertEquals(SourceCheck.AUTH, sync.check(localApp, service, source, "wrong-password").result)
        assertEquals(SourceCheck.UNREACHABLE, sync.check(localApp, service, source.copy(port = 1), from.password).result)

        val job = sync.runBlocking(localApp, service, source, from.password) { println("[$service] $it") }
        println("[$service] ${job.state}: ${job.message}")
        assertEquals("succeeded", job.state, job.message)
        val qty =
            DatabaseTarget.resolve(engine, localApp, service).connect {
                it.createStatement().executeQuery("SELECT qty FROM stock WHERE id = 1").use { r ->
                    r.next()
                    r.getInt(1)
                }
            }
        assertEquals(5, qty)
    }
}
