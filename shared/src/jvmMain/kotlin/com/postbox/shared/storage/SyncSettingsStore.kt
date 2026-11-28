package com.postbox.shared.storage

actual fun createSyncSettingsStore(context: PlatformContext): SyncSettingsStore =
    object : SyncSettingsStore {
        private var polling = true
        private var notifications = true

        override fun backgroundPollingEnabled(): Boolean = polling

        override fun setBackgroundPollingEnabled(enabled: Boolean) {
            polling = enabled
        }

        override fun notificationsEnabled(): Boolean = notifications

        override fun setNotificationsEnabled(enabled: Boolean) {
            notifications = enabled
        }
    }
