package com.postbox.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Opens the SQLite database at [sqlitePath]. */
fun connectDatabase(sqlitePath: String): Database =
    Database.connect("jdbc:sqlite:$sqlitePath", driver = "org.sqlite.JDBC")

/** Runs [block] in a single transaction on [db]. */
fun <T> dbQuery(db: Database, block: JdbcTransaction.() -> T): T = transaction(db) { block() }

private val isoUtc: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/**
 * Formats [instant] as fixed-width ISO-8601 UTC text (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).
 * Every timestamp written to a TEXT column MUST go through this formatter: fixed
 * width keeps plain TEXT comparison (used for `expires_at`) consistent with
 * chronological order, while variable-width formats such as [Instant.toString]
 * compare lexicographically wrong at fraction boundaries.
 */
fun isoUtc(instant: Instant): String = isoUtc.format(instant)

/**
 * Current time as fixed-width ISO-8601 UTC text. Fixed width keeps plain TEXT
 * comparison (used for `expires_at`) consistent with chronological order.
 */
fun nowIso(): String = isoUtc(Instant.now())
