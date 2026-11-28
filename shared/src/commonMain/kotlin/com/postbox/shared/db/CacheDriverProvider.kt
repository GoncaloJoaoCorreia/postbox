package com.postbox.shared.db

import app.cash.sqldelight.db.SqlDriver
import com.postbox.shared.storage.PlatformContext

interface CacheDriverProvider {
    fun createDriver(): SqlDriver
}

expect fun createCacheDriverProvider(context: PlatformContext): CacheDriverProvider
