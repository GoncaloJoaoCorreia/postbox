package com.postbox.shared.storage

import android.content.Context

actual fun createSyncSettingsStore(context: PlatformContext): SyncSettingsStore =
    object : SyncSettingsStore {
        private val prefs = context.getSharedPreferences("postbox_sync_settings", Context.MODE_PRIVATE)

        override fun backgroundPollingEnabled(): Boolean = prefs.getBoolean("background_polling", true)

        override fun setBackgroundPollingEnabled(enabled: Boolean) {
            prefs.edit().putBoolean("background_polling", enabled).apply()
        }

        override fun notificationsEnabled(): Boolean = prefs.getBoolean("notifications", true)

        override fun setNotificationsEnabled(enabled: Boolean) {
            prefs.edit().putBoolean("notifications", enabled).apply()
        }
    }
