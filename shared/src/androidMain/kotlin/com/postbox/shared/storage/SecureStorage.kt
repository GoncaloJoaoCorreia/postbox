package com.postbox.shared.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private fun encryptedPrefs(context: Context, fileName: String) = EncryptedSharedPreferences.create(
    context,
    fileName,
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

actual fun createTokenStorage(context: Any): TokenStorage {
    val ctx = context as Context
    return object : TokenStorage {
        private val prefs = encryptedPrefs(ctx, "postbox_tokens")

        override fun accessToken(): String? = prefs.getString("access_token", null)

        override fun refreshToken(): String? = prefs.getString("refresh_token", null)

        override fun save(accessToken: String, refreshToken: String) {
            prefs.edit()
                .putString("access_token", accessToken)
                .putString("refresh_token", refreshToken)
                .apply()
        }

        override fun clear() {
            prefs.edit().clear().apply()
        }
    }
}

actual fun createInstanceSessionStore(context: Any): InstanceSessionStore {
    val ctx = context as Context
    return object : InstanceSessionStore {
        private val prefs = encryptedPrefs(ctx, "postbox_session")

        override fun baseUrl(): String? = prefs.getString("base_url", null)

        override fun save(baseUrl: String) {
            prefs.edit().putString("base_url", baseUrl.trimEnd('/')).apply()
        }

        override fun clear() {
            prefs.edit().clear().apply()
        }
    }
}
