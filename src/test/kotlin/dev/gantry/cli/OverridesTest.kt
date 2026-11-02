package dev.gantry.cli

import dev.gantry.engine.Catalog
import dev.gantry.engine.GantryException
import dev.gantry.engine.SourceSettings
import dev.gantry.share.ExportedPath
import dev.gantry.share.ImportRequest
import dev.gantry.share.ImportedService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class OverridesTest {
    private val catalog = Catalog.load()
    private val request =
        ImportRequest(
            "shop",
            mapOf(
                "postgres" to
                    ImportedService(mapOf("version" to "16", "migrations" to ""), SourceSettings(host = "postgres.example", port = 5432)),
            ),
            emptyMap(),
        )

    @Test
    fun `set and source override fields`() {
        val result =
            applyOverrides(
                catalog,
                request,
                listOf("postgres.version=15"),
                listOf("postgres.host=localhost", "postgres.port=6543"),
                emptyMap(),
            )
        val postgres = result.services.getValue("postgres")
        assertEquals("15", postgres.options["version"])
        assertEquals("localhost", postgres.source?.host)
        assertEquals(6543, postgres.source?.port)
        assertEquals(
            5432,
            request.services
                .getValue("postgres")
                .source
                ?.port,
        )
    }

    @Test
    fun `repo override points migrations at the given checkout`() {
        val exported = mapOf("postgres" to ExportedPath("shop-api", "db/migration"))
        val result = applyOverrides(catalog, request, emptyList(), emptyList(), mapOf("shop-api" to "/work/shop-api"), exported)
        assertEquals("/work/shop-api/db/migration", result.services.getValue("postgres").options["migrations"])
    }

    @Test
    fun `bad override syntax is rejected`() {
        assertThrows<GantryException> { applyOverrides(catalog, request, listOf("version=15"), emptyList(), emptyMap()) }
            .let { assertEquals("expected <service>.<option>=<value>, got 'version=15'", it.message) }
        assertThrows<GantryException> { applyOverrides(catalog, request, emptyList(), listOf("postgres.port=abc"), emptyMap()) }
        assertThrows<GantryException> { applyOverrides(catalog, request, emptyList(), listOf("postgres.nope=1"), emptyMap()) }
        assertThrows<GantryException> { applyOverrides(catalog, request, listOf("valkey.x=1"), emptyList(), emptyMap()) }
    }

    @Test
    fun `sync source fields override the saved source`() {
        val saved = SourceSettings("stage", 5432, "shop", "me")

        val result = applySourceFields(saved, listOf("host=prod", "port=6543", "user=ci"))

        assertEquals(SourceSettings("prod", 6543, "shop", "ci"), result)
    }

    @Test
    fun `sync source fields are validated`() {
        val saved = SourceSettings("stage", 5432, "shop", "me")
        assertThrows<GantryException> { applySourceFields(saved, listOf("port=abc")) }
        assertThrows<GantryException> { applySourceFields(saved, listOf("password=x")) }
        assertThrows<GantryException> { applySourceFields(saved, listOf("host")) }
    }

    @Test
    fun `a set secret is labelled, with a warning when it replaces a kept one`() {
        assertEquals("kept", secretLabel(kept = true, overridden = false))
        assertEquals("generated", secretLabel(kept = false, overridden = false))
        assertEquals("set", secretLabel(kept = false, overridden = true))
        assertEquals(
            "set (kept value replaced — the existing database won't accept it until its volume is recreated)",
            secretLabel(kept = true, overridden = true),
        )
    }
}
