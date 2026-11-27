package com.postbox.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.postbox.shared.storage.PlatformContext

actual fun createCacheDriverProvider(context: PlatformContext): CacheDriverProvider =
    object : CacheDriverProvider {
        override fun createDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    }
