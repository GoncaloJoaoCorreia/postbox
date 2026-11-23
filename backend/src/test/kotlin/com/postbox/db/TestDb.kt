package com.postbox.db

import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File

/**
 * Creates a fresh temp SQLite file under `build/tmp` (never the gitignored
 * `data/` dir), applies all migrations, and returns a connection to it.
 */
internal fun freshTestDatabase(name: String): Database {
    val dir = File("build/tmp/test-db/$name")
    dir.mkdirs()
    val file = File(dir, "postbox.db")
    file.delete()
    Migrations.run(file.absolutePath)
    return connectDatabase(file.absolutePath)
}
