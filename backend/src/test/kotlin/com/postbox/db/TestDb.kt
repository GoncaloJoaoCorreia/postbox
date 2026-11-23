package com.postbox.db

import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File

/**
 * Creates a fresh temp SQLite file under `build/tmp` (never the gitignored
 * `data/` dir), applies all migrations, and returns the single open connection
 * the migrations ran on. Callers must close it with [closeDatabase] when done
 * (the tests wrap their body in try/finally).
 */
internal fun freshTestDatabase(name: String): Database {
    val dir = File("build/tmp/test-db/$name")
    dir.deleteRecursively()
    dir.mkdirs()
    return Migrations.run(File(dir, "postbox.db").absolutePath)
}
