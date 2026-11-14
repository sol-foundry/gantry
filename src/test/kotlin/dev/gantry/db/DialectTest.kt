package dev.gantry.db

import dev.gantry.engine.GantryException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DialectTest {
    @Test
    fun `gantry's own connections name themselves gantry, so the query log skips them`() {
        kotlin.test.assertTrue(Dialect.Postgres.jdbcUrl(5432, "shop").endsWith("?ApplicationName=gantry"))
        kotlin.test.assertTrue("connectionAttributes=program_name:gantry" in Dialect.MySql.jdbcUrl(3306, "shop"))
    }

    @Test
    fun `local database names are encoded in the url`() {
        val postgres = Dialect.Postgres.jdbcUrl(5432, "shop?socketFactory=x")
        val mysql = Dialect.MySql.jdbcUrl(3306, "shop?allowLoadLocalInfile=true")

        assertEquals("jdbc:postgresql://127.0.0.1:5432/shop%3FsocketFactory%3Dx?ApplicationName=gantry", postgres)
        kotlin.test.assertTrue(mysql.startsWith("jdbc:mysql://127.0.0.1:3306/shop%3FallowLoadLocalInfile%3Dtrue?"))
        listOf("allowLoadLocalInfile=false", "allowUrlInLocalInfile=false", "autoDeserialize=false").forEach {
            kotlin.test.assertTrue(it in mysql, "local mysql url pins $it")
        }
    }

    private val email = ColumnDef("email", "varchar(255)", nullable = false, default = "''")

    @Test
    fun `identifiers are quoted per dialect`() {
        assertEquals("\"a\"\"b\"", Dialect.Postgres.quote("a\"b"))
        assertEquals("`a``b`", Dialect.MySql.quote("a`b"))
        assertEquals("\"public\".\"users\"", Dialect.Postgres.qualified("public", "users"))
        assertEquals("`users`", Dialect.MySql.qualified("ignored", "users"))
    }

    @Test
    fun `column definitions carry nullability default and extra`() {
        assertEquals("\"email\" varchar(255) NOT NULL DEFAULT ''", Dialect.Postgres.columnDefinition(email))
        assertEquals(
            "`id` bigint NOT NULL AUTO_INCREMENT",
            Dialect.MySql.columnDefinition(ColumnDef("id", "bigint", nullable = false, extra = "AUTO_INCREMENT")),
        )
    }

    @Test
    fun `postgres alter emits only changed clauses and renames last`() {
        val current = ColumnInfo("mail", "text", nullable = true, default = null, primaryKey = false, extra = "")

        val sql = Dialect.Postgres.alterColumn("public", "users", current, email)

        assertEquals(
            "ALTER TABLE \"public\".\"users\" ALTER COLUMN \"mail\" TYPE varchar(255) USING \"mail\"::varchar(255), " +
                "ALTER COLUMN \"mail\" SET NOT NULL, ALTER COLUMN \"mail\" SET DEFAULT '';\n" +
                "ALTER TABLE \"public\".\"users\" RENAME COLUMN \"mail\" TO \"email\"",
            sql,
        )
        assertFailsWith<GantryException> {
            Dialect.Postgres.alterColumn("public", "users", current, ColumnDef("mail", "text", nullable = true))
        }
    }

    @Test
    fun `mysql alter keeps auto_increment unless replaced`() {
        val id = ColumnInfo("id", "bigint", nullable = false, default = null, primaryKey = true, extra = "AUTO_INCREMENT")

        val sql = Dialect.MySql.alterColumn("", "users", id, ColumnDef("id", "bigint unsigned", nullable = false))

        assertEquals("ALTER TABLE `users` CHANGE COLUMN `id` `id` bigint unsigned NOT NULL AUTO_INCREMENT", sql)
    }

    @Test
    fun `sql fragments cannot smuggle a second statement`() {
        listOf("int; DROP TABLE x", "int -- c", "int /* c */").forEach { type ->
            assertFailsWith<GantryException> { Dialect.Postgres.columnDefinition(ColumnDef("a", type)) }
        }
    }

    @Test
    fun `next migration version follows applied and on-disk versions`() {
        assertEquals(1, nextVersion(emptyList(), emptyList()))
        assertEquals(4, nextVersion(listOf("1", "2"), listOf("V1__a.sql", "V3_1__c.sql", "R__views.sql", "notes.txt")))
        assertEquals(8, nextVersion(listOf("7.2"), listOf("V2__b.sql")))
    }
}

class ResultReaderTest {
    @Test
    fun `long cells are clipped and reported`() {
        val reader = ResultReader(2)
        reader.accept { column -> if (column == 0) "short" else "x".repeat(MAX_CELL_CHARS + 10) }

        val result = reader.result(listOf("a", "b"), hasMore = false)

        assertEquals(MAX_CELL_CHARS, result.rows[0][1]!!.length)
        assertEquals(listOf(listOf(0, 1)), result.clippedCells)
        assertEquals("1 long value(s) cut at 64 KB", result.note)
    }

    @Test
    fun `reading stops once the result holds too many characters`() {
        val reader = ResultReader(1)
        val big = "y".repeat(MAX_CELL_CHARS)
        var accepted = 0
        while (reader.accept { big }) accepted++

        assertEquals(MAX_RESULT_CHARS / MAX_CELL_CHARS, accepted)
        assertEquals(true, reader.full)
    }
}
