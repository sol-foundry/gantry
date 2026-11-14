package dev.gantry.gateway

import dev.gantry.engine.GantryException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private const val HASH_BYTES = 6

/**
 * The jars gantry-auth runs from. The container mounts a copy under `home/runtime` rather than the install itself,
 * so upgrading or reinstalling gantry never pulls files out from under a running gateway.
 */
object GatewayRuntime {
    /** This build's version, or `dev` when it runs from build output without a manifest. */
    fun version(): String = GatewayRuntime::class.java.`package`?.implementationVersion ?: "dev"

    /** The installed distribution's `lib/` directory: the one holding the jar this class was loaded from. */
    fun installedLib(): Path {
        val location =
            Path.of(
                GatewayRuntime::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        if (!location.isRegularFile() || !location.name.endsWith(".jar")) {
            throw GantryException(
                "the gateway runs gantry-auth from gantry's jars, but this gantry is not an installed build " +
                    "(running from $location); use an installed gantry (./gradlew installDist)",
            )
        }
        return location.parent
    }

    /**
     * Copies [lib] to `home/runtime/gantry-<version>-<hash>/lib` unless that copy already exists, and returns the
     * `gantry-<version>-<hash>` directory. The hash covers the jars' names, sizes and modification times, so a
     * rebuild under the same version gets a fresh copy.
     */
    fun stage(
        home: Path,
        lib: Path = installedLib(),
        version: String = version(),
    ): Path {
        if (!Files.isDirectory(lib)) throw GantryException("gantry's lib directory $lib does not exist")
        val jars = Files.list(lib).use { files -> files.filter { it.isRegularFile() }.sorted().toList() }
        val digest = MessageDigest.getInstance("SHA-256")
        jars.forEach { digest.update("${it.name}\u0000${Files.size(it)}\u0000${Files.getLastModifiedTime(it).toMillis()}\n".toByteArray()) }
        val hash = digest.digest().take(HASH_BYTES).joinToString("") { "%02x".format(it) }
        val runtime = home.resolve("runtime")
        val target = runtime.resolve("gantry-$version-$hash")
        if (Files.isDirectory(target.resolve("lib"))) return target

        // Copied aside and renamed into place, so a half-finished copy is never taken for a complete one.
        Files.createDirectories(runtime)
        val temp = Files.createTempDirectory(runtime, ".staging-")
        try {
            val tempLib = Files.createDirectories(temp.resolve("lib"))
            jars.forEach { Files.copy(it, tempLib.resolve(it.name), StandardCopyOption.COPY_ATTRIBUTES) }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: FileSystemException) {
                // Another gantry process (the dashboard, say) staged the same jars first.
                if (!Files.isDirectory(target.resolve("lib"))) throw e
            }
        } finally {
            if (Files.exists(temp)) temp.toFile().deleteRecursively()
        }
        return target
    }
}
