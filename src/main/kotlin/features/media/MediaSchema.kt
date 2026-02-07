package com.connor.features.media

import kotlinx.serialization.Serializable

@Serializable
data class MediaUploadResponse(
    val url: String,
    val type: String
)
