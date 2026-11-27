package com.postbox.shared.instance

import io.ktor.http.URLProtocol
import io.ktor.http.Url

object InstanceUrl {
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if ("://" in trimmed) {
            val scheme = trimmed.substringBefore("://")
            if (scheme.isEmpty() || !scheme.all { it.isLetterOrDigit() || it in "+-." }) return null
        }
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val authority = withScheme.substringAfter("://")
            .substringBefore("/").substringBefore("?").substringBefore("#")
        if (authority.isBlank()) return null
        val url = runCatching { Url(withScheme) }.getOrNull() ?: return null
        if (url.protocol != URLProtocol.HTTPS && url.protocol != URLProtocol.HTTP) return null
        if (url.host.isBlank()) return null
        val port = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
        return "${url.protocol.name.lowercase()}://${url.host}$port"
    }

    fun isPlainHttp(normalizedUrl: String): Boolean = normalizedUrl.startsWith("http://")
}
