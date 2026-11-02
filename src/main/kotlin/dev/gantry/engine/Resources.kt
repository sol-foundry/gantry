package dev.gantry.engine

import java.net.URI
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/** Gives classpath directories a [Path], whether the app runs from a jar or from build output. */
internal object Resources {
    fun directory(name: String): Path {
        val url =
            Resources::class.java.classLoader.getResource(name)
                ?: throw IllegalStateException("classpath resource '$name' is missing")
        val uri = url.toURI()
        return if (uri.scheme == "jar") jarFileSystem(uri).getPath("/$name") else Path.of(uri)
    }

    private fun jarFileSystem(uri: URI) =
        try {
            FileSystems.newFileSystem(uri, emptyMap<String, Any>())
        } catch (_: FileSystemAlreadyExistsException) {
            FileSystems.getFileSystem(uri)
        }

    fun copyTree(
        from: Path,
        to: Path,
    ) {
        Files.walk(from).use { paths ->
            paths.forEach { source ->
                val target = to.resolve(from.relativize(source).toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
