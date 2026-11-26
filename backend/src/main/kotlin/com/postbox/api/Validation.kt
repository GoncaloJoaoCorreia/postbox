package com.postbox.api

internal const val MinPasswordLength = 8
internal const val MaxTrackingNumberLength = 64
internal const val MaxDisplayNameLength = 120

private val emailPattern = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
private val trackingNumberPattern = Regex("""^[A-Za-z0-9][A-Za-z0-9-]*$""")

internal fun isValidEmail(email: String): Boolean = emailPattern.matches(email)

internal fun displayNameFor(email: String): String = email.substringBefore('@')

internal fun normalizeTrackingNumber(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > MaxTrackingNumberLength) return null
    if (!trackingNumberPattern.matches(trimmed)) return null
    return trimmed
}

internal fun isValidDisplayName(displayName: String): Boolean =
    displayName.isNotBlank() && displayName.length <= MaxDisplayNameLength
