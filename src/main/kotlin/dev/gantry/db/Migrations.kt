package dev.gantry.db

import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class MigrationRow(
    val version: String?,
    val description: String,
    val type: String,
    val script: String,
    val state: String,
    val installedOn: String?,
    val executionMs: Int?,
)

@Serializable
data class MigrationStatus(
    val service: String,
    val location: String,
    val locationExists: Boolean,
    val current: String?,
    val pending: Int,
    val migrations: List<MigrationRow>,
)

@Serializable
data class MigrationOutcome(
    val message: String,
    val status: MigrationStatus,
)

/**
 * Flyway, embedded: each SQL service reads versioned migrations from its `migrations` option, which can
 * point at the service repo's own migration folder. Runs over the published host port, like the explorer.
 */
class Migrations(
    private val engine: Engine,
) {
    fun status(
        app: String,
        service: String,
    ): MigrationStatus = status(DatabaseTarget.resolve(engine, app, service))

    fun run(
        app: String,
        service: String,
        action: String,
    ): MigrationOutcome {
        val target = DatabaseTarget.resolve(engine, app, service)
        val flyway = flyway(target)
        val message =
            flywayCall {
                when (action) {
                    "migrate" -> {
                        val result = migrating(target).migrate()
                        if (result.migrationsExecuted == 0) {
                            "schema is up to date"
                        } else {
                            "applied ${result.migrationsExecuted} migration(s); now at version ${result.targetSchemaVersion}"
                        }
                    }

                    "validate" -> {
                        val result = flyway.validateWithResult()
                        if (result.validationSuccessful) {
                            "migrations are valid"
                        } else {
                            throw GantryException(
                                "validation failed:\n" +
                                    result.invalidMigrations.joinToString("\n") {
                                        "  ${it.version ?: it.filepath}: ${it.errorDetails.errorMessage}"
                                    },
                            )
                        }
                    }

                    "repair" -> {
                        val result = flyway.repair()
                        "repaired: ${result.repairActions.ifEmpty { listOf("nothing to repair") }.joinToString()}"
                    }

                    "baseline" -> {
                        flyway.baseline()
                        "baselined at version 1"
                    }

                    "clean" -> {
                        val result = flyway.clean()
                        "dropped every object in ${result.schemasCleaned.ifEmpty { listOf(target.database) }.joinToString()}"
                    }

                    else -> {
                        throw GantryException("unknown action '$action' (migrate, validate, repair, baseline, clean)")
                    }
                }
            }
        return MigrationOutcome(message, status(target))
    }

    /** Writes `V<next>__<description>.sql` into the migrations directory and returns its file name. */
    fun create(
        app: String,
        service: String,
        description: String,
        sql: String,
    ): String {
        val target = DatabaseTarget.resolve(engine, app, service)
        if (sql.isBlank()) throw GantryException("migration is empty")
        val slug =
            description
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifEmpty { throw GantryException("description is required") }
        val applied = runCatching { status(target).migrations.mapNotNull { it.version } }.getOrDefault(emptyList())
        val next = nextVersion(applied, scripts(target.migrations))
        Files.createDirectories(target.migrations)
        val file = target.migrations.resolve("V${next}__$slug.sql")
        Files.writeString(file, sql)
        return file.fileName.toString()
    }

    fun script(
        app: String,
        service: String,
        name: String,
    ): String {
        val target = DatabaseTarget.resolve(engine, app, service)
        if (name !in scripts(target.migrations)) throw GantryException("no migration file '$name' in ${target.migrations}")
        return Files.readString(target.migrations.resolve(name))
    }

    /** Runs `migrate` for every SQL service that opted in with `migrate_on_up`; returns one line per service. */
    fun migrateOnUp(app: String): List<String> =
        DatabaseTarget.all(engine, app).filter { it.migrateOnUp }.map { target ->
            val outcome = runCatching { run(app, target.service, "migrate").message }
            "${target.service}: ${outcome.getOrElse { "migration failed: ${it.message}" }}"
        }

    private fun status(target: DatabaseTarget): MigrationStatus {
        val exists = Files.isDirectory(target.migrations)
        val info = flywayCall { flyway(target).info() }
        return MigrationStatus(
            service = target.service,
            location = target.migrations.toString(),
            locationExists = exists,
            current = info.current()?.version?.version,
            pending = info.pending().size,
            migrations =
                info.all().map {
                    MigrationRow(
                        version = it.version?.version,
                        description = it.description,
                        type = it.type.name(),
                        script = it.script,
                        state = it.state.displayName,
                        installedOn = it.installedOn?.toInstant()?.toString(),
                        executionMs = it.executionTime,
                    )
                },
        )
    }

    private fun flyway(target: DatabaseTarget): Flyway =
        Flyway
            .configure(javaClass.classLoader)
            .dataSource(target.jdbcUrl, target.user, target.password)
            .locations("filesystem:${target.migrations}")
            // Local databases only: let the dashboard reset a schema while iterating on migrations.
            .cleanDisabled(false)
            .load()

    /**
     * Seed files (initdb) often create objects before Flyway first runs. Baselining at 0 accepts them and
     * still applies every migration from V1, instead of refusing a non-empty schema.
     */
    private fun migrating(target: DatabaseTarget): Flyway =
        Flyway
            .configure(javaClass.classLoader)
            .configuration(flyway(target).configuration)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .baselineDescription("<< existing objects (seed files) >>")
            .load()

    private fun <T> flywayCall(block: () -> T): T =
        try {
            block()
        } catch (e: FlywayException) {
            val cause = generateSequence<Throwable>(e) { it.cause }.last()
            val connect = cause is java.sql.SQLException && cause.sqlState?.startsWith("08") == true
            throw GantryException(
                if (connect) {
                    "cannot connect to the database (is the app up?): ${cause.message}"
                } else {
                    e.message
                        ?: "flyway failed"
                },
            )
        }

    private fun scripts(dir: Path): List<String> =
        if (!Files.isDirectory(dir)) {
            emptyList()
        } else {
            Files.list(dir).use { files ->
                files
                    .map { it.fileName.toString() }
                    .filter { it.endsWith(".sql") }
                    .sorted()
                    .toList()
            }
        }
}

private val versionedFile = Regex("""^V([0-9][0-9._]*)__""")

/**
 * The next whole version after everything applied or on disk: V1_2 and V1.2 count as 1, so new files never
 * sort before existing ones. Applied versions matter when the directory lags the database.
 */
internal fun nextVersion(
    applied: List<String>,
    files: List<String>,
): Int {
    val onDisk = files.mapNotNull { versionedFile.find(it)?.groupValues?.get(1) }
    return (applied + onDisk).maxOfOrNull { it.split('.', '_').first().toIntOrNull() ?: 0 }?.plus(1) ?: 1
}
