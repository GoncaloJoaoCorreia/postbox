package com.postbox.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/**
 * Opens the SQLite database at [sqlitePath]. Every new physical connection runs
 * `PRAGMA foreign_keys = ON` and `PRAGMA busy_timeout = 5000` — SQLite leaves
 * foreign-key enforcement off by default and allows a single writer, and both
 * pragmas are per-connection, so they must be set wherever a connection is
 * created, not once per process. The busy timeout makes concurrent writers
 * (e.g. a list refresh probing several parcels at once) wait instead of failing
 * immediately with SQLITE_BUSY.
 */
fun connectDatabase(sqlitePath: String): Database =
    Database.connect(
        "jdbc:sqlite:$sqlitePath",
        driver = "org.sqlite.JDBC",
        setupConnection = { connection ->
            connection.createStatement().use {
                it.execute("PRAGMA foreign_keys = ON")
                it.execute("PRAGMA busy_timeout = 5000")
            }
        },
    )

/** Closes the connections behind [db] and unregisters it from Exposed. */
fun closeDatabase(db: Database) {
    TransactionManager.closeAndUnregister(db)
}

/**
 * Runs [block] in a single transaction on [db]. The transaction is executed via
 * `suspendTransaction` inside a [runBlocking] on an explicitly owned
 * [Dispatchers.IO] context, so the blocking JDBC work never runs on a caller's
 * coroutine dispatcher (e.g. the Ktor/Netty event loop).
 */
fun <T> dbQuery(db: Database, block: JdbcTransaction.() -> T): T =
    runBlocking(Dispatchers.IO) {
        suspendTransaction(db) { block() }
    }

/** Suspend variant of [dbQuery] for callers already inside a coroutine. */
suspend fun <T> dbQuerySuspend(db: Database, block: suspend JdbcTransaction.() -> T): T =
    suspendTransaction(db) { block() }

private val isoUtc: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Exactly the fixed-width shape produced by [isoUtc]. */
private val isoUtcPattern = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")

/**
 * Formats [instant] as fixed-width ISO-8601 UTC text (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).
 * Every timestamp written to a TEXT column MUST go through this formatter: fixed
 * width keeps plain TEXT comparison (used for `expires_at`) consistent with
 * chronological order, while variable-width formats such as [Instant.toString]
 * compare lexicographically wrong at fraction boundaries.
 */
fun isoUtc(instant: Instant): String = isoUtc.format(instant)

/** [kotlin.time.Instant] overload of [isoUtc] for coroutine clocks. */
fun isoUtc(instant: kotlin.time.Instant): String = isoUtc(instant.toJavaInstant())

/**
 * Current time as fixed-width ISO-8601 UTC text. Fixed width keeps plain TEXT
 * comparison (used for `expires_at`) consistent with chronological order.
 */
fun nowIso(): String = isoUtc(Instant.now())

/** True when [value] has exactly the fixed-width format produced by [isoUtc]. */
fun isIsoUtc(value: String): Boolean = isoUtcPattern.matches(value)

/** Parses the fixed-width ISO-8601 UTC text produced by [isoUtc]. */
fun parseIsoUtc(value: String): kotlin.time.Instant = Instant.from(isoUtc.parse(value)).toKotlinInstant()
