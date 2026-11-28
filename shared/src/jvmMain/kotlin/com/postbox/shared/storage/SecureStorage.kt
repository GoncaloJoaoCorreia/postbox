package com.postbox.shared.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

private class InMemoryTokenStorage : TokenStorage {
    private var access: String? = null
    private var refresh: String? = null
    private var session: String? = null
    private val changeSignal = MutableSharedFlow<Unit>(replay = 1)

    override fun accessToken(): String? = access

    override fun refreshToken(): String? = refresh

    override fun sessionId(): String? = session

    override fun save(accessToken: String, refreshToken: String, sessionId: String) {
        access = accessToken
        refresh = refreshToken
        session = sessionId
        changeSignal.tryEmit(Unit)
    }

    override fun clear() {
        access = null
        refresh = null
        session = null
        changeSignal.tryEmit(Unit)
    }

    override fun changes(): Flow<Unit> = changeSignal
}

private class InMemoryInstanceSessionStore : InstanceSessionStore {
    private var url: String? = null

    override fun baseUrl(): String? = url

    override fun save(baseUrl: String) {
        url = baseUrl
    }

    override fun clear() {
        url = null
    }
}

actual fun createTokenStorage(context: PlatformContext): TokenStorage = InMemoryTokenStorage()

actual fun createInstanceSessionStore(context: PlatformContext): InstanceSessionStore = InMemoryInstanceSessionStore()
