package com.connor.features.media

import kotlinx.serialization.Serializable

/**
 * 媒体上传成功响应
 *
 * @param url 媒体的公开访问 URL（如 /uploads/{uuid}.jpg）
 * @param type 媒体类型（IMAGE 或 VIDEO）
 */
@Serializable
data class MediaUploadResponse(
    val url: String,
    val type: String
)
