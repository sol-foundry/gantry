package dev.gantry.dblog

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CertificatesTest {
    @Test
    fun `the certificate is created once and reused`() {
        val dir = createTempDirectory("gantry-tls")
        val first = Certificates.ensure(dir, "shop")
        assertEquals(dir.resolve("tls/shop.p12"), first.keyStore)
        val bytes = first.keyStore.readBytes()

        val second = Certificates.ensure(dir, "shop")
        assertEquals(first, second)
        assertContentEquals(bytes, second.keyStore.readBytes())

        val store =
            KeyStore
                .getInstance(
                    "PKCS12",
                ).apply { Files.newInputStream(first.keyStore).use { load(it, first.password.toCharArray()) } }
        val cert = store.getCertificate(store.aliases().toList().single()) as X509Certificate
        assertEquals("CN=localhost", cert.subjectX500Principal.name)
        val names = cert.subjectAlternativeNames.map { it[1].toString() }.toSet()
        assertEquals(setOf("localhost", "127.0.0.1", "postgres", "mysql", "postgres-db", "mysql-db"), names)
        assertTrue(cert.notAfter.time - cert.notBefore.time >= 3650L * 24 * 3600 * 1000)
        assertTrue(store.getKey(store.aliases().toList().single(), first.password.toCharArray()) != null)

        val password = dir.resolve("tls/shop.password")
        if (Files.getFileStore(password).supportsFileAttributeView("posix")) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(password))
        }
    }

    @Test
    fun `each app gets its own certificate`() {
        val dir = createTempDirectory("gantry-tls")
        val a = Certificates.ensure(dir, "a")
        val b = Certificates.ensure(dir, "b")
        assertTrue(a.password != b.password)
        assertTrue(!a.keyStore.readBytes().contentEquals(b.keyStore.readBytes()))
    }
}
