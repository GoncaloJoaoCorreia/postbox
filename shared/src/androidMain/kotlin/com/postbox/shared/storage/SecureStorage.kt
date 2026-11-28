package com.postbox.shared.storage

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

private fun encryptedPrefs(context: PlatformContext, fileName: String) = EncryptedSharedPreferences.create(
    context,
    fileName,
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

actual fun createTokenStorage(context: PlatformContext): TokenStorage {
    return object : TokenStorage {
        private val prefs = encryptedPrefs(context, "postbox_tokens")
        private val changeSignal = MutableSharedFlow<Unit>(replay = 1)

        override fun accessToken(): String? = prefs.getString("access_token", null)

        override fun refreshToken(): String? = prefs.getString("refresh_token", null)

        override fun sessionId(): String? = prefs.getString("session_id", null)

        override fun save(accessToken: String, refreshToken: String, sessionId: String) {
            prefs.edit()
                .putString("access_token", accessToken)
                .putString("refresh_token", refreshToken)
                .putString("session_id", sessionId)
                .apply()
            changeSignal.tryEmit(Unit)
        }

        override fun clear() {
            prefs.edit().clear().apply()
            changeSignal.tryEmit(Unit)
        }

        override fun changes(): Flow<Unit> = changeSignal
    }
}

actual fun createInstanceSessionStore(context: PlatformContext): InstanceSessionStore {
    return object : InstanceSessionStore {
        private val prefs = encryptedPrefs(context, "postbox_session")

        override fun baseUrl(): String? = prefs.getString("base_url", null)

        override fun save(baseUrl: String) {
            prefs.edit().putString("base_url", baseUrl.trimEnd('/')).apply()
        }

        override fun clear() {
            prefs.edit().clear().apply()
        }
    }
}
