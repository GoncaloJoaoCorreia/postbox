package com.postbox.shared.storage

private class InMemoryTokenStorage : TokenStorage {
    private var access: String? = null
    private var refresh: String? = null

    override fun accessToken(): String? = access

    override fun refreshToken(): String? = refresh

    override fun save(accessToken: String, refreshToken: String) {
        access = accessToken
        refresh = refreshToken
    }

    override fun clear() {
        access = null
        refresh = null
    }
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

actual fun createTokenStorage(context: Any): TokenStorage = InMemoryTokenStorage()

actual fun createInstanceSessionStore(context: Any): InstanceSessionStore = InMemoryInstanceSessionStore()
