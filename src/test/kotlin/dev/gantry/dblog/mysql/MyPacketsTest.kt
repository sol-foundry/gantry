package dev.gantry.dblog.mysql

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MyPacketsTest {
    @Test
    fun `a stream splits into packets across partial reads`() {
        val stream =
            MyPackets.encode(0, My.query("select 1").payload) + MyPackets.encode(1, ByteArray(0)) +
                MyPackets.encode(7, byteArrayOf(9, 8, 7))
        val reader = MyPacketReader()

        val packets = stream.toList().chunked(3).flatMap { reader.feed(it.toByteArray()) }

        assertEquals(listOf(0, 1, 7), packets.map { it.seq })
        assertContentEquals(My.query("select 1").payload, packets[0].payload)
        assertEquals(0, packets[1].payload.size)
        assertContentEquals(byteArrayOf(9, 8, 7), packets[2].payload)
        assertFalse(packets.any { it.cut })
    }

    @Test
    fun `a payload split into 16 MB chunks is joined, and past the cap keeps its head`() {
        val payload = ByteArray(0xFFFFFF + 10) { (it % 251).toByte() }
        val wire = MyPackets.encode(3, payload) + MyPackets.encode(5, byteArrayOf(1))
        assertEquals(4 + 0xFFFFFF + 4 + 10 + 4 + 1, wire.size)

        val whole = MyPacketReader(maxPayload = 32 * 1024 * 1024).feedChunked(wire)
        assertEquals(listOf(3, 5), whole.map { it.seq })
        assertTrue(payload.contentEquals(whole[0].payload), "joined payload differs: ${whole[0]}")
        assertFalse(whole[0].cut)

        val capped = MyPacketReader(maxPayload = 1000).feedChunked(wire)
        assertEquals(2, capped.size)
        assertContentEquals(payload.copyOf(1000), capped[0].payload)
        assertEquals(payload.size.toLong(), capped[0].length)
        assertTrue(capped[0].cut)
        assertContentEquals(byteArrayOf(1), capped[1].payload)
    }

    @Test
    fun `the greeting advertises TLS only when the proxy has a certificate and keeps its length`() {
        val greeting = My.greeting(My.BASE or My.SSL or My.COMPRESS or My.DEPRECATE_EOF).payload

        val withTls = MyHandshake.rewriteGreeting(greeting, tls = true)
        val withoutTls = MyHandshake.rewriteGreeting(greeting, tls = false)

        assertEquals(greeting.size, withTls.size)
        assertEquals(greeting.size, withoutTls.size)
        val tlsCaps = MyHandshake.greetingCapabilities(withTls)
        val plainCaps = MyHandshake.greetingCapabilities(withoutTls)
        assertTrue(tlsCaps and My.SSL != 0L)
        assertEquals(0L, plainCaps and My.SSL)
        assertEquals(0L, tlsCaps and My.COMPRESS)
        assertTrue(tlsCaps and My.DEPRECATE_EOF != 0L)
        val differing = greeting.indices.filter { greeting[it] != withoutTls[it] }
        assertTrue(differing.size in 1..4, "only capability bytes change: $differing")
    }

    @Test
    fun `an SSLRequest is told apart from a full handshake response`() {
        assertTrue(MyHandshake.isSslRequest(My.sslRequest(My.CLIENT or My.SSL).payload))
        assertFalse(MyHandshake.isSslRequest(My.login(My.CLIENT or My.SSL).payload))
        assertFalse(MyHandshake.isSslRequest(My.sslRequest(My.CLIENT).payload))
    }

    private fun MyPacketReader.feedChunked(wire: ByteArray): List<MyPacket> {
        val out = ArrayList<MyPacket>()
        var i = 0
        while (i < wire.size) {
            val n = minOf(65_536, wire.size - i)
            out += feed(wire, i, n)
            i += n
        }
        return out
    }
}
