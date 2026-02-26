package com.connor.domain.model

/**
 * 媒体 ID - 类型安全的标识符
 */
@JvmInline
value class MediaId(val value: Long)

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
 * 头像配置 - 独立于媒体的约束（仅图片, 2MB）
 */
data class AvatarConfig(
    val uploadDir: String = "uploads/avatars",
    val maxFileSize: Long = 2 * 1024 * 1024,
    val allowedTypes: Set<String> = setOf("image/jpeg", "image/png", "image/webp")
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
