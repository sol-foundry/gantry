package dev.gantry.dblog

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Socket plumbing shared by the database proxies. */
internal object Relay {
    /** Copies [input] to [output] until either ends, handing each chunk to [observe] before or after it is sent. */
    fun pump(
        input: InputStream,
        output: OutputStream,
        observeFirst: Boolean,
        observe: (ByteArray, Int) -> Unit,
    ) {
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return
                if (observeFirst) guarded { observe(buffer, n) }
                output.write(buffer, 0, n)
                output.flush()
                if (!observeFirst) guarded { observe(buffer, n) }
            }
        } catch (_: IOException) {
            // Either side closed.
        }
    }

    /** Recording must never break the relay. */
    inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Ignored: the bytes are relayed regardless.
        }
    }

    /** The server-side TLS context presenting the certificate in [tls]. */
    fun serverContext(tls: TlsFiles): SSLContext {
        val password = tls.password.toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(tls.keyStore).use { load(it, password) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
    }
}
