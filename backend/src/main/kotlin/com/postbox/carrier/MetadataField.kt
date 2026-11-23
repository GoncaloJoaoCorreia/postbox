package com.postbox.carrier

/**
 * A metadata field an adapter requires; consumed by `CarrierAdapter.requiredMetadata`.
 */
data class MetadataField(
    val name: String,
    val label: String,
    val hint: String,
    val validationRegex: String?,
)
