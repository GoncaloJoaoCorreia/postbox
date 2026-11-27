package com.postbox.shared.api

import com.postbox.shared.domain.model.MetadataField

class ApiException(
    val code: String,
    override val message: String,
    val status: Int,
    val fields: List<MetadataField>? = null
) : Exception(message)
