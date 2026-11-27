package com.postbox.shared.db

import app.cash.sqldelight.db.SqlDriver

interface CacheDriverProvider {
    fun createDriver(): SqlDriver
}

expect fun createCacheDriverProvider(context: Any): CacheDriverProvider
