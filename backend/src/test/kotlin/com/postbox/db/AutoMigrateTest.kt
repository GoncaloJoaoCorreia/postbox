package com.postbox.db

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AutoMigrateTest {

    @Test
    fun `fresh database file is migrated automatically on init`() {
        val db = freshTestDatabase("automigrate")
        try {
            val versions = dbQuery(db) {
                exec("SELECT version FROM schema_version ORDER BY version") { rs ->
                    buildList { while (rs.next()) add(rs.getInt(1)) }
                } ?: emptyList()
            }
            assertEquals(listOf(1, 2, 3, 4, 5, 6), versions)
            val tables = dbQuery(db) {
                exec("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name") { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                } ?: emptyList()
            }
            assertEquals(listOf("device_sessions", "parcel_events", "parcels", "schema_version", "users"), tables)
            val userColumns = dbQuery(db) {
                exec("SELECT name FROM pragma_table_info('users') ORDER BY name") { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                } ?: emptyList()
            }
            assertEquals(
                listOf("created_at", "display_name", "email", "id", "is_active", "password_hash", "role"),
                userColumns,
            )
            val parcelColumns = dbQuery(db) {
                exec("SELECT name FROM pragma_table_info('parcels') ORDER BY name") { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                } ?: emptyList()
            }
            assertTrue("detection_method" in parcelColumns, "V2 must add parcels.detection_method, got: $parcelColumns")
            assertTrue("last_check_error" in parcelColumns, "V3 must add parcels.last_check_error, got: $parcelColumns")
            assertTrue("estimated_delivery_at" in parcelColumns, "V4 must add parcels.estimated_delivery_at, got: $parcelColumns")
            assertTrue("last_check_error_code" in parcelColumns, "V5 must add parcels.last_check_error_code, got: $parcelColumns")
            assertTrue("rate_limited_until" in parcelColumns, "V5 must add parcels.rate_limited_until, got: $parcelColumns")
            val sessionColumns = dbQuery(db) {
                exec("SELECT name FROM pragma_table_info('device_sessions') ORDER BY name") { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                } ?: emptyList()
            }
            assertEquals(
                listOf(
                    "created_at", "device_name", "expires_at", "id", "last_used_at",
                    "previous_token_hash", "revoked_at", "token_hash", "user_id",
                ),
                sessionColumns,
                "V6 must create the device_sessions table with the session columns",
            )
            // applied_at must be fixed-width ISO-8601 UTC text so plain TEXT ordering stays chronological
            val appliedAt = dbQuery(db) {
                exec("SELECT applied_at FROM schema_version WHERE version = 1") { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
            assertTrue(appliedAt != null && isIsoUtc(appliedAt), "applied_at must be fixed-width ISO UTC text, got: $appliedAt")
        } finally {
            closeDatabase(db)
        }
    }

    @Test
    fun `re-running migrations on an up-to-date database is a no-op`() {
        val dir = File("build/tmp/test-db/automigrate-rerun")
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "postbox.db")
        val first = Migrations.run(file.absolutePath)
        val second = Migrations.run(file.absolutePath)
        try {
            val versions = dbQuery(second) {
                exec("SELECT version FROM schema_version ORDER BY version") { rs ->
                    buildList { while (rs.next()) add(rs.getInt(1)) }
                } ?: emptyList()
            }
            assertEquals(listOf(1, 2, 3, 4, 5, 6), versions, "each migration must be applied exactly once; a re-run must not re-apply it")
        } finally {
            closeDatabase(first)
            closeDatabase(second)
        }
    }

    @Test
    fun `a failing migration rolls back atomically and records nothing`() {
        val db = freshTestDatabase("automigrate-rollback")
        try {
            val bad = Migrations.Migration(2, "V2__bad.sql", "db/migration/V2__bad.sql")
            assertFailsWith<Exception> {
                Migrations.applyMigration(
                    db, bad,
                    "CREATE TABLE doomed (id TEXT PRIMARY KEY); INSERT INTO missing_table VALUES (1);",
                )
            }
            val versions = dbQuery(db) {
                exec("SELECT version FROM schema_version ORDER BY version") { rs ->
                    buildList { while (rs.next()) add(rs.getInt(1)) }
                } ?: emptyList()
            }
            assertEquals(listOf(1, 2, 3, 4, 5, 6), versions, "a failed migration must not be recorded in schema_version")
            val doomed = dbQuery(db) {
                exec("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'doomed'") { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                } ?: emptyList()
            }
            assertEquals(emptyList(), doomed, "statements applied before the failure must roll back")
        } finally {
            closeDatabase(db)
        }
    }

    @Test
    fun `migration discovery also reads jar archives (packaged distribution)`() {
        val dir = File("build/tmp/test-db/jar-discovery")
        dir.deleteRecursively()
        dir.mkdirs()
        val jarPath = File(dir, "fake-app.jar")
        JarOutputStream(jarPath.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("db/migration/"))
            jar.closeEntry()
            jar.putNextEntry(JarEntry("db/migration/V7__from_jar.sql"))
            jar.write("SELECT 1;".toByteArray())
            jar.closeEntry()
            jar.putNextEntry(JarEntry("db/migration/V3__also_jar.sql"))
            jar.write("SELECT 1;".toByteArray())
            jar.closeEntry()
            jar.putNextEntry(JarEntry("db/migration/not-a-migration.txt"))
            jar.write("nope".toByteArray())
            jar.closeEntry()
            jar.putNextEntry(JarEntry("db/migration/nested/V9__nested.sql"))
            jar.write("SELECT 1;".toByteArray())
            jar.closeEntry()
        }
        JarFile(jarPath).use { jar ->
            val found = Migrations.migrationsInJar(jar)
            assertEquals(
                listOf(3, 7),
                found.map { it.version },
                "only direct V<n>__*.sql entries must be discovered, sorted by version",
            )
            assertEquals("db/migration/V7__from_jar.sql", found.last().resourcePath)
        }
    }

    @Test
    fun `splitStatements keeps semicolons inside strings, comments and trigger bodies intact`() {
        val sql = """
            -- a comment with a ; semicolon
            CREATE TABLE t (note TEXT);
            INSERT INTO t VALUES ('a;b');
            /* block ; comment */
            CREATE TRIGGER trg AFTER INSERT ON t BEGIN
                UPDATE t SET note = 'x;y' WHERE rowid = NEW.rowid;
                INSERT INTO t VALUES ('z');
            END;
            INSERT INTO t VALUES ('tail');
        """.trimIndent()
        val statements = Migrations.splitStatements(sql)
        assertEquals(4, statements.size, "statements: $statements")
        assertTrue(statements[1].contains("'a;b'"), "semicolon inside a string literal must not split: ${statements[1]}")
        assertTrue(statements[2].contains("END"), "trigger body must stay one statement: ${statements[2]}")
        assertTrue(statements[2].contains("'x;y'"), "semicolons inside the trigger body must not split: ${statements[2]}")
        assertEquals("INSERT INTO t VALUES ('tail')", statements[3])
    }
}
