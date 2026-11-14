package dev.gantry.dblog.postgres

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PgFramesTest {
    private val sslRequest = PgMessage('\u0000', PgPayload().int32(80877103).toByteArray())

    @Test
    fun `a client stream splits into an SSL request, the startup and typed messages across partial reads`() {
        val startup = Pg.startup("user" to "app", "application_name" to "psql")
        val stream =
            PgFrames.encode(sslRequest) + PgFrames.encode(startup) + PgFrames.encode(Pg.query("select 1")) +
                PgFrames.encode(Pg.sync())
        val reader = PgFrameReader(startupPhase = true)

        val messages = stream.toList().chunked(3).flatMap { reader.feed(it.toByteArray()) }

        assertEquals(listOf('\u0000', '\u0000', 'Q', 'S'), messages.map { it.type })
        assertContentEquals(startup.payload, messages[1].payload)
        assertContentEquals(Pg.query("select 1").payload, messages[2].payload)
    }

    @Test
    fun `a server stream has no startup message`() {
        val reader = PgFrameReader(startupPhase = false)
        val bytes = PgFrames.encode(Pg.commandComplete("SELECT 1")) + PgFrames.encode(Pg.ready())

        val messages = reader.feed(bytes)

        assertEquals(listOf('C', 'Z'), messages.map { it.type })
    }

    @Test
    fun `a frame larger than the cap keeps its head and the stream stays in step`() {
        val reader = PgFrameReader(startupPhase = false, maxPayload = 8)
        val big = Pg.dataRow("x".repeat(100))
        val bytes = PgFrames.encode(big) + PgFrames.encode(Pg.ready())

        val messages = bytes.toList().chunked(7).flatMap { reader.feed(it.toByteArray()) }

        assertEquals(listOf('D', 'Z'), messages.map { it.type })
        assertEquals(8, messages[0].payload.size)
        assertTrue(messages[0].cut)
    }
}
