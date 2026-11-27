package com.postbox.shared.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.postbox.shared.cache.db.PostboxCache

actual fun createCacheDriverProvider(context: Any): CacheDriverProvider {
    val ctx = context as Context
    return object : CacheDriverProvider {
        override fun createDriver(): SqlDriver =
            AndroidSqliteDriver(PostboxCache.Schema, ctx, "postbox-cache.db")
    }
}
