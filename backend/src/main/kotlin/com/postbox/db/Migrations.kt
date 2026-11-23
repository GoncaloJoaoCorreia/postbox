package com.postbox.db

import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.Collections

/**
 * Small custom migration runner (no exposed-migration dependency): discovers
 * `V<n>__*.sql` resources under classpath `db/migration/`, applies pending ones in
 * ascending version order, and records each applied version in `schema_version`.
 * Each migration file runs in its own transaction together with its `schema_version`
 * insert, so a failure rolls back atomically.
 */
object Migrations {

    private val log = LoggerFactory.getLogger("com.postbox.db.Migrations")

    private val migrationName = Regex("""^V(\d+)__.*\.sql$""")

    private data class Migration(val version: Int, val name: String, val resourcePath: String)

    fun run(sqlitePath: String) {
        // Exposed 1.6.1 maps jdbc:sqlite to org.sqlite.JDBC + SQLiteDialect automatically.
        Database.connect("jdbc:sqlite:$sqlitePath")
        val applied: Set<Int> = transaction {
            exec(
                "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)"
            )
            exec("SELECT version FROM schema_version") { rs ->
                buildSet { while (rs.next()) add(rs.getInt(1)) }
            } ?: emptySet()
        }
        val pending = discoverMigrations().filter { it.version !in applied }
        if (pending.isEmpty()) {
            log.info("Schema up to date, no pending migrations ({} applied)", applied.size)
            return
        }
        for (migration in pending) {
            val sql = readResource(migration.resourcePath)
            transaction {
                for (statement in splitStatements(sql)) {
                    exec(statement)
                }
                exec(
                    "INSERT INTO schema_version(version, applied_at) VALUES (?, ?)",
                    args = listOf(
                        IntegerColumnType() to migration.version,
                        TextColumnType() to Instant.now().toString(),
                    ),
                )
            }
            log.info("Applied migration V{} ({})", migration.version, migration.name)
        }
    }

    /** Lists `db/migration/V<n>__*.sql` classpath resources (file: protocol under `./gradlew run`). */
    private fun discoverMigrations(): List<Migration> {
        val classLoader = Thread.currentThread().contextClassLoader
        val migrations = mutableListOf<Migration>()
        for (url in Collections.list(classLoader.getResources("db/migration"))) {
            if (url.protocol != "file") continue
            for (file in File(url.toURI()).listFiles() ?: emptyArray()) {
                val match = migrationName.matchEntire(file.name) ?: continue
                migrations += Migration(
                    version = match.groupValues[1].toInt(),
                    name = file.name,
                    resourcePath = "db/migration/${file.name}",
                )
            }
        }
        return migrations.sortedBy { it.version }
    }

    private fun readResource(path: String): String {
        val classLoader = Thread.currentThread().contextClassLoader
        return classLoader.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("Migration resource not found on classpath: $path")
    }

    /** Splits a migration file into statements on `;` at line ends. */
    private fun splitStatements(sql: String): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (line in sql.lineSequence()) {
            current.appendLine(line)
            if (line.trimEnd().endsWith(";")) {
                chunks += current.toString()
                current.clear()
            }
        }
        chunks += current.toString()
        return chunks
            .map { it.trim().removeSuffix(";").trim() }
            .filter { it.isNotEmpty() }
    }
}
