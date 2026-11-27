package com.postbox.shared

import com.postbox.shared.api.PostboxApi
import com.postbox.shared.cache.db.PostboxCache
import com.postbox.shared.db.CacheDriverProvider
import com.postbox.shared.db.createCacheDriverProvider
import com.postbox.shared.repository.ParcelRepository
import com.postbox.shared.storage.InstanceSessionStore
import com.postbox.shared.storage.PlatformContext
import com.postbox.shared.storage.TokenStorage
import com.postbox.shared.storage.createInstanceSessionStore
import com.postbox.shared.storage.createTokenStorage

class PostboxDependencies(context: PlatformContext) {
    val tokenStorage: TokenStorage = createTokenStorage(context)
    val sessionStore: InstanceSessionStore = createInstanceSessionStore(context)
    private val cacheDriverProvider: CacheDriverProvider = createCacheDriverProvider(context)
    private val db by lazy { PostboxCache(cacheDriverProvider.createDriver()) }
    private var api: PostboxApi? = null

    fun api(): PostboxApi {
        api?.let { return it }
        val baseUrl = sessionStore.baseUrl() ?: throw IllegalStateException("No Postbox instance configured")
        return PostboxApi(baseUrl, tokenStorage).also { api = it }
    }

    fun repository(): ParcelRepository = ParcelRepository(api(), db)
}
