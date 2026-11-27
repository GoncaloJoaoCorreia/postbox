package com.postbox.shared.storage

interface InstanceSessionStore {
    fun baseUrl(): String?
    fun save(baseUrl: String)
    fun clear()
}
