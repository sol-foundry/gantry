package dev.gantry.db

import dev.gantry.engine.Engine
import dev.gantry.engine.GantryException
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** How to reach one SQL service of an app from the host, plus where its Flyway migrations live. */
data class DatabaseTarget(
    val app: String,
    val service: String,
    val dialect: Dialect,
    val jdbcUrl: String,
    val database: String,
    val user: String,
    val password: String,
    val migrations: Path,
    val migrateOnUp: Boolean,
) {
    fun <T> connect(block: (Connection) -> T): T {
        val connection =
            try {
                DriverManager.getConnection(jdbcUrl, user, password)
            } catch (e: SQLException) {
                // 28xxx: bad password or role, 3D000 / 1049: unknown database, 1045: access denied (MySQL).
                // Both images apply credentials only when the data volume is first created.
                val credentials = e.sqlState?.startsWith("28") == true || e.sqlState == "3D000" || e.errorCode in setOf(1045, 1049)
                val hint =
                    if (credentials) {
                        " (name, user and password only take effect on a fresh data volume; revert the option or destroy the app)"
                    } else {
                        " (is the app up?)"
                    }
                throw GantryException("cannot connect to $service$hint: ${e.message}")
            }
        return connection.use(block)
    }

    companion object {
        fun resolve(
            engine: Engine,
            app: String,
            service: String,
        ): DatabaseTarget {
            val appState = engine.loadApp(app)
            val id = engine.catalog.resolve(service).id
            val dialect = Dialect.forService(id)
            val state = appState.service(id) ?: throw GantryException("app '$app' has no $id service")
            val options = engine.serviceOptions(appState, id)
            val database = options.getValue("name")
            return DatabaseTarget(
                app = app,
                service = id,
                dialect = dialect,
                jdbcUrl = dialect.jdbcUrl(state.ports.getValue("main"), database),
                database = database,
                user = options.getValue("user"),
                password = options.getValue("password"),
                migrations = Path.of(options.getValue("migrations")),
                migrateOnUp = options["migrate_on_up"] == "true",
            )
        }

        /** Every SQL service of [app]. */
        fun all(
            engine: Engine,
            app: String,
        ): List<DatabaseTarget> =
            engine
                .loadApp(app)
                .services
                .map { it.id }
                .filter { it in Dialect.services }
                .map { resolve(engine, app, it) }
    }
}
