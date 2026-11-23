package com.postbox.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoMigrateTest {

    @Test
    fun `fresh database file is migrated to V1 automatically on init`() {
        val dir = File("build/tmp/test-db/automigrate")
        dir.mkdirs()
        val file = File(dir, "postbox.db")
        file.delete()

        Migrations.run(file.absolutePath)

        val db = connectDatabase(file.absolutePath)
        val versions = dbQuery(db) {
            exec("SELECT version FROM schema_version ORDER BY version") { rs ->
                buildList { while (rs.next()) add(rs.getInt(1)) }
            } ?: emptyList()
        }
        assertEquals(listOf(1), versions)
        val tables = dbQuery(db) {
            exec("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name") { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            } ?: emptyList()
        }
        assertEquals(listOf("invitations", "parcel_events", "parcels", "schema_version", "users"), tables)
    }
}
