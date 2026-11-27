package com.postbox.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.postbox.shared.cache.db.PostboxCache
import com.postbox.shared.storage.PlatformContext

actual fun createCacheDriverProvider(context: PlatformContext): CacheDriverProvider =
    object : CacheDriverProvider {
        override fun createDriver(): SqlDriver =
            AndroidSqliteDriver(PostboxCache.Schema, context, "postbox-cache.db")
    }
