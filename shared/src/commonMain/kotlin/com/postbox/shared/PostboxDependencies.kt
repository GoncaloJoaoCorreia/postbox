package com.postbox.shared

import com.postbox.shared.api.PostboxApi
import com.postbox.shared.auth.AuthRepository
import com.postbox.shared.cache.db.PostboxCache
import com.postbox.shared.db.CacheDriverProvider
import com.postbox.shared.db.createCacheDriverProvider
import com.postbox.shared.instance.InstanceRepository
import com.postbox.shared.repository.ParcelRepository
import com.postbox.shared.storage.InstanceSessionStore
import com.postbox.shared.storage.PlatformContext
import com.postbox.shared.storage.TokenStorage
import com.postbox.shared.storage.createInstanceSessionStore
import com.postbox.shared.storage.createTokenStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class PostboxDependencies(
    context: PlatformContext,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    val tokenStorage: TokenStorage = createTokenStorage(context)
    val sessionStore: InstanceSessionStore = createInstanceSessionStore(context)
    private val cacheDriverProvider: CacheDriverProvider = createCacheDriverProvider(context)
    private val db by lazy { PostboxCache(cacheDriverProvider.createDriver()) }
    private var api: PostboxApi? = null
    private var apiBaseUrl: String? = null
    private var parcelRepository: ParcelRepository? = null
    private var authRepository: AuthRepository? = null
    private var repositoryBaseUrl: String? = null

    fun api(): PostboxApi {
        val baseUrl = sessionStore.baseUrl() ?: throw IllegalStateException("No Postbox instance configured")
        val cached = api
        if (cached != null && apiBaseUrl == baseUrl) return cached
        cached?.close()
        val created = PostboxApi(baseUrl, tokenStorage)
        api = created
        apiBaseUrl = baseUrl
        return created
    }

    fun probeApi(baseUrl: String): PostboxApi = PostboxApi(baseUrl, tokenStorage)

    fun repository(): ParcelRepository {
        api()
        val cached = parcelRepository
        if (cached != null && repositoryBaseUrl == apiBaseUrl) return cached
        val created = ParcelRepository(api(), db, ioDispatcher)
        parcelRepository = created
        repositoryBaseUrl = apiBaseUrl
        return created
    }

    fun authRepository(): AuthRepository {
        api()
        val cached = authRepository
        if (cached != null && repositoryBaseUrl == apiBaseUrl) return cached
        val created = AuthRepository(api(), tokenStorage) { repository().clearCache() }
        authRepository = created
        repositoryBaseUrl = apiBaseUrl
        return created
    }

    fun instanceRepository(): InstanceRepository =
        InstanceRepository(sessionStore, ::clearLocalData) { probeApi(it) }

    private suspend fun clearLocalData() {
        tokenStorage.clear()
        repository().clearCache()
    }
}
