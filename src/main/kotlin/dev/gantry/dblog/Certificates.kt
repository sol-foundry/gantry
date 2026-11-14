package dev.gantry.dblog

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/** A PKCS12 [keyStore] holding the proxy's key and certificate, opened with [password]. */
data class TlsFiles(
    val keyStore: Path,
    val password: String,
)

/** The self-signed certificate an app's database proxies present to clients that ask for TLS. */
object Certificates {
    private val names = listOf("localhost", "postgres", "mysql", "postgres-db", "mysql-db")
    private val random = SecureRandom()

    /** Returns `tls/<app>.p12` and its password under [dir], creating both the first time. */
    @Synchronized
    fun ensure(
        dir: Path,
        app: String,
    ): TlsFiles {
        val tls = Files.createDirectories(dir.resolve("tls"))
        val store = tls.resolve("$app.p12")
        val passwordFile = tls.resolve("$app.password")
        if (Files.exists(store) && Files.exists(passwordFile)) {
            listOf(store, passwordFile).forEach(::restrict)
            return TlsFiles(store, Files.readString(passwordFile).trim())
        }
        val password = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val tmpStore = createOwnerOnly(tls.resolve("$app.p12.tmp"))
        Files.newOutputStream(tmpStore).use { generate(password).store(it, password.toCharArray()) }
        Files.writeString(createOwnerOnly(tls.resolve("$app.password.tmp")), password).let {
            Files.move(it, passwordFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        Files.move(tmpStore, store, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return TlsFiles(store, password)
    }

    private val ownerOnly = PosixFilePermissions.fromString("rw-------")

    /** An empty [file] only its owner can read (the proxy reads it as root). */
    private fun createOwnerOnly(file: Path): Path {
        Files.deleteIfExists(file)
        if (Files.getFileStore(file.parent).supportsFileAttributeView("posix")) {
            return Files.createFile(file, PosixFilePermissions.asFileAttribute(ownerOnly))
        }
        return Files.createFile(file)
    }

    private fun restrict(file: Path) {
        if (Files.getFileStore(file).supportsFileAttributeView("posix") && Files.getPosixFilePermissions(file) != ownerOnly) {
            runCatching { Files.setPosixFilePermissions(file, ownerOnly) }
        }
    }

    private fun generate(password: String): KeyStore {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        val now = Instant.now()
        val subject = X500Name("CN=localhost")
        val sans =
            (names.map { GeneralName(GeneralName.dNSName, it) } + GeneralName(GeneralName.iPAddress, "127.0.0.1")).toTypedArray()
        val holder =
            JcaX509v3CertificateBuilder(
                subject,
                BigInteger(64, random).add(BigInteger.ONE),
                Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(3651))),
                subject,
                keys.public,
            ).addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
                .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
                .addExtension(Extension.subjectAlternativeName, false, GeneralNames(sans))
                .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("gantry", keys.private, password.toCharArray(), arrayOf(cert))
        }
    }
}
