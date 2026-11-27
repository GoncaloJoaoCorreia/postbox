package com.postbox.shared.auth

import com.postbox.shared.api.PostboxApi
import com.postbox.shared.domain.model.DeviceSessionInfo
import com.postbox.shared.domain.model.Session
import com.postbox.shared.domain.model.UserAccount
import com.postbox.shared.storage.TokenStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

class AuthRepository(
    private val api: PostboxApi,
    private val tokenStorage: TokenStorage,
    private val clearCache: suspend () -> Unit
) {
    fun hasSession(): Boolean = tokenStorage.accessToken() != null && tokenStorage.refreshToken() != null

    fun sessionState(): Flow<Boolean> = tokenStorage.changes()
        .map { hasSession() }
        .onStart { emit(hasSession()) }

    fun currentSessionId(): String? = tokenStorage.sessionId()

    suspend fun login(email: String, password: String, deviceName: String? = null): Session =
        api.login(email, password, deviceName).also {
            tokenStorage.save(it.accessToken, it.refreshToken, it.sessionId)
        }

    suspend fun logout() {
        val sessionId = tokenStorage.sessionId()
        if (sessionId != null) runCatching { api.revokeDevice(sessionId) }
        tokenStorage.clear()
        clearCache()
    }

    suspend fun changePassword(newPassword: String) {
        api.changePassword(newPassword)
        tokenStorage.clear()
    }

    suspend fun me(): UserAccount = api.me()

    suspend fun listDevices(): List<DeviceSessionInfo> = api.listDevices()

    suspend fun revokeDevice(id: String) {
        api.revokeDevice(id)
        if (id == tokenStorage.sessionId()) {
            tokenStorage.clear()
            clearCache()
        }
    }
}
