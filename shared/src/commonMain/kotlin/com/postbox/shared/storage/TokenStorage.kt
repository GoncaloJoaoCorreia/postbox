package com.postbox.shared.storage

interface TokenStorage {
    fun accessToken(): String?
    fun refreshToken(): String?
    fun sessionId(): String?
    fun save(accessToken: String, refreshToken: String, sessionId: String)
    fun clear()
}
