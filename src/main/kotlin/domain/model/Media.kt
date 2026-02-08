package com.connor.domain.model

/**
 * 媒体 ID - 类型安全的标识符
 */
@JvmInline
value class MediaId(val value: String)

/**
 * 已上传的媒体信息 - 存储操作返回的结果
 */
data class UploadedMedia(
    val id: MediaId,
    val url: MediaUrl,
    val type: MediaType,
    val fileSize: Long,
    val contentType: String,
    val uploadedAt: Long = System.currentTimeMillis()
)

/**
 * 媒体配置 - 从应用配置读取的常量
 */
data class MediaConfig(
    val uploadDir: String,
    val maxFileSize: Long, // 字节
    val allowedTypes: Set<String>, // MIME types: image/jpeg, video/mp4 等
    val enableDatabase: Boolean = false
)
