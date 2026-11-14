package dev.gantry.engine

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A random URL-safe secret (24 characters) for generated passwords. */
fun generateSecret(): String {
    val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Passwords the user typed for remote databases, kept in `home/secrets.json` (owner-only), never in app.json. */
class SecretStore(
    home: Path,
) {
    private val file = home.resolve("secrets.json")
    private val lockFile = home.resolve("secrets.json.lock")
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun password(
        app: String,
        service: String,
    ): String? = locked { read()["$app/$service"] }

    fun setPassword(
        app: String,
        service: String,
        password: String,
    ) = locked { write(read() + ("$app/$service" to password)) }

    /** A secret the app's `gateway.yml` refers to as `{{secret:NAME}}`. */
    fun secret(
        app: String,
        name: String,
    ): String? = password(app, "$GATEWAY_SECRET$name")

    fun setSecret(
        app: String,
        name: String,
        value: String,
    ) = setPassword(app, "$GATEWAY_SECRET$name", value)

    /** The names of the app's gateway secrets, sorted; never their values. */
    fun secretNames(app: String): List<String> {
        val prefix = "$app/$GATEWAY_SECRET"
        return locked { read().keys }.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.sorted()
    }

    /** Drops one gateway secret; false when it was not set. */
    fun forgetSecret(
        app: String,
        name: String,
    ): Boolean =
        locked {
            val all = read()
            val key = "$app/$GATEWAY_SECRET$name"
            (key in all).also { if (it) write(all - key) }
        }

    /** Drops one service's password, or every password of the app when [service] is null. */
    fun forget(
        app: String,
        service: String? = null,
    ) = locked {
        val all = read()
        val kept = all.filterKeys { if (service == null) !it.startsWith("$app/") else it != "$app/$service" }
        if (kept.size != all.size) write(kept)
    }

    /** The CLI and the dashboard are separate processes, so read-modify-write needs a file lock; the JVM lock covers threads, since a file lock cannot be taken twice in one JVM. */
    private fun <T> locked(action: () -> T): T =
        jvmLock.withLock {
            Files.createDirectories(lockFile.parent)
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { action() }
            }
        }

    private fun read(): Map<String, String> =
        if (!Files.exists(file)) {
            emptyMap()
        } else {
            try {
                json.decodeFromString(serializer, Files.readString(file))
            } catch (e: SerializationException) {
                throw GantryException("$file is unreadable (${e.message}); fix or delete it (saved passwords will be lost)")
            }
        }

    private fun write(secrets: Map<String, String>) {
        Files.createDirectories(file.parent)
        val temp =
            Files.createTempFile(
                file.parent,
                "secrets",
                ".tmp",
                PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)),
            )
        Files.writeString(temp, json.encodeToString(serializer, secrets))
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val GATEWAY_SECRET = "gateway-secret:"
        val jvmLock = ReentrantLock()
    }
}
