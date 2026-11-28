package com.postbox.shared.storage

interface SyncSettingsStore {
    fun backgroundPollingEnabled(): Boolean
    fun setBackgroundPollingEnabled(enabled: Boolean)
    fun notificationsEnabled(): Boolean
    fun setNotificationsEnabled(enabled: Boolean)
}

expect fun createSyncSettingsStore(context: PlatformContext): SyncSettingsStore
