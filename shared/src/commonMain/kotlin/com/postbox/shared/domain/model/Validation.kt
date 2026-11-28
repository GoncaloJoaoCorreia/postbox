package com.postbox.shared.domain.model

const val MaxTrackingNumberLength = 64
const val MaxDisplayNameLength = 120

private val trackingNumberPattern = Regex("^[A-Za-z0-9][A-Za-z0-9-]*$")

fun normalizeTrackingNumber(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > MaxTrackingNumberLength) return null
    if (!trackingNumberPattern.matches(trimmed)) return null
    return trimmed
}

fun isValidDisplayName(displayName: String): Boolean =
    displayName.isNotBlank() && displayName.length <= MaxDisplayNameLength

fun MetadataField.accepts(value: String): Boolean =
    validationRegex?.let { Regex(it).matches(value) } ?: true
