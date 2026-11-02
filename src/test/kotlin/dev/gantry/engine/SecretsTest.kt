package dev.gantry.engine

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretsTest {
    @Test
    fun `passwords persist per app and service with owner-only permissions`() {
        val home = Files.createTempDirectory("gantry-secrets")
        SecretStore(home).setPassword("shop", "postgres", "s3cret")
        assertEquals("s3cret", SecretStore(home).password("shop", "postgres"))
        assertNull(SecretStore(home).password("shop", "mysql"))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(home.resolve("secrets.json")),
        )
    }

    @Test
    fun `forget removes one service or the whole app`() {
        val store = SecretStore(Files.createTempDirectory("gantry-secrets"))
        store.setPassword("shop", "postgres", "a")
        store.setPassword("shop", "mysql", "b")
        store.setPassword("billing", "postgres", "c")

        store.forget("shop", "mysql")
        assertNull(store.password("shop", "mysql"))
        assertEquals("a", store.password("shop", "postgres"))

        store.forget("shop")
        assertNull(store.password("shop", "postgres"))
        assertEquals("c", store.password("billing", "postgres"))
    }

    @Test
    fun `generated secrets are url-safe and long`() {
        val a = generateSecret()
        val b = generateSecret()
        assertTrue(a.length >= 24 && Regex("^[A-Za-z0-9_-]+$").matches(a))
        assertNotEquals(a, b)
    }

    @Test
    fun `two stores on one home keep each other's updates`() {
        val home = Files.createTempDirectory("gantry-secrets")
        val a = SecretStore(home)
        val b = SecretStore(home)
        a.setPassword("shop", "postgres", "1")
        b.setPassword("shop", "mysql", "2")
        a.setPassword("billing", "postgres", "3")
        assertEquals("2", SecretStore(home).password("shop", "mysql"))
        assertEquals("1", SecretStore(home).password("shop", "postgres"))
        assertEquals("3", SecretStore(home).password("billing", "postgres"))
    }

    @Test
    fun `a corrupt file is a user-facing error and does not block destroy`() {
        val home = Files.createTempDirectory("gantry-secrets")
        val engine = Engine(home = home, dockerCommand = NO_DOCKER)
        engine.create("shop", services = listOf("postgres"))
        Files.writeString(home.resolve("secrets.json"), "{not json")
        val e = assertFailsWith<GantryException> { engine.secrets.password("shop", "postgres") }
        assertTrue("secrets.json" in e.message!!)
        engine.docker.destroy("shop")
        assertFalse(Files.exists(engine.appDir("shop")))
    }
}
