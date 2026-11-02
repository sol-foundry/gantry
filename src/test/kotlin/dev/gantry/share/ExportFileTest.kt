package dev.gantry.share

import dev.gantry.engine.GantryException
import dev.gantry.engine.SourceSettings
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExportFileTest {
    @Test
    fun `round trip keeps every field`() {
        val original =
            ExportFile(
                gantry = 1,
                app = "myapp",
                services =
                    mapOf(
                        "postgres" to
                            ExportedService(
                                options = mapOf("POSTGRES_PASSWORD" to "secret"),
                                migrations = ExportedPath(repo = "github.com/myorg/migrations", path = "migrations/sql"),
                                source = SourceSettings(host = "prod.example.com", port = 5432, database = "mydb", user = "admin"),
                            ),
                        "redis" to
                            ExportedService(
                                options = mapOf("REDIS_PORT" to "6379"),
                                migrations = null,
                                source = null,
                            ),
                    ),
                envNames = mapOf("POSTGRES_HOST" to "PG_HOST", "REDIS_HOST" to ""),
            )

        val encoded = original.encode()
        val decoded = parseExportFile(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `unknown format version is rejected`() {
        val invalidJson = """{"gantry":2,"app":"x","services":{}}"""
        val exception =
            assertFailsWith<GantryException> {
                parseExportFile(invalidJson)
            }
        assertContains(exception.message!!, "unsupported export format 2")
    }

    @Test
    fun `encoded file carries the format version`() {
        val encoded = ExportFile(app = "x", services = emptyMap()).encode()

        assertContains(encoded, "\"gantry\": 1")
    }

    @Test
    fun `file without a format version is rejected`() {
        val exception =
            assertFailsWith<GantryException> {
                parseExportFile("""{"app":"x","services":{}}""")
            }
        assertContains(exception.message!!, "not a Gantry export: missing format version")
    }

    @Test
    fun `garbage is rejected`() {
        val exception =
            assertFailsWith<GantryException> {
                parseExportFile("nope")
            }
        assertContains(exception.message!!, "not a Gantry export")
    }
}
