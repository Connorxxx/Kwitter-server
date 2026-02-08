package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.raise.either
import com.connor.domain.failure.MediaError
import com.connor.domain.model.MediaConfig
import com.connor.domain.model.MediaId
import com.connor.domain.model.MediaType
import com.connor.domain.model.UploadedMedia
import com.connor.domain.repository.MediaStorageRepository
import java.security.MessageDigest

/**
 * 媒体上传的 UseCase 命令
 *
 * 文件名仅用于日志和错误消息，实际存储使用文件内容的 MD5 哈希值
 */
data class UploadMediaCommand(
    val fileName: String,        // 原始文件名（仅用于日志）
    val contentType: String,     // MIME 类型
    val fileBytes: ByteArray     // 文件内容（用于计算哈希）
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UploadMediaCommand

        if (fileName != other.fileName) return false
        if (contentType != other.contentType) return false
        if (!fileBytes.contentEquals(other.fileBytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + fileBytes.contentHashCode()
        return result
    }
}

/**
 * 媒体上传 UseCase - 编排业务规则和存储流程
 *
 * 职责：
 * 1. 验证文件类型（白名单检查）
 * 2. 验证文件大小限制
 * 3. 根据文件内容计算 MD5 哈希作为文件名
 * 4. 调用 Repository 执行存储
 * 5. 返回 Either<MediaError, UploadedMedia> 处理结果或错误
 *
 * 使用 MD5 哈希的优势：
 * - ✓ 防止重复上传：同样内容会使用相同文件名，自动去重
 * - ✓ 节省存储空间：重复文件不需要额外存储
 * - ✓ 简化验证：文件名格式固定，验证逻辑简单
 * - ✓ 唯一性：MD5 冲突概率极低（实际可用 SHA256 提高安全性）
 */
class UploadMediaUseCase(
    private val storageRepository: MediaStorageRepository,
    private val config: MediaConfig
) {
    suspend operator fun invoke(command: UploadMediaCommand): Either<MediaError, UploadedMedia> {
        return either {
            // 1. 验证文件类型
            if (command.contentType !in config.allowedTypes) {
                raise(MediaError.InvalidFileType(command.contentType, config.allowedTypes))
            }

            // 2. 验证文件大小
            val fileSize = command.fileBytes.size.toLong()
            if (fileSize > config.maxFileSize) {
                raise(MediaError.FileTooLarge(fileSize, config.maxFileSize))
            }

            // 3. 生成安全文件名（MD5 哈希 + 扩展名）
            val safeFileName = generateSafeFileName(command.fileBytes, command.contentType)

            // 4. 调用 Repository 上传文件
            storageRepository.upload(command.fileBytes, safeFileName)
                .mapLeft { error -> error }  // 传递存储层的错误
                .bind()
        }
    }

    /**
     * 根据文件内容和 MIME 类型生成安全的文件名
     *
     * 规则：
     * - 使用文件内容的 MD5 哈希作为文件名（防重复、节省空间）
     * - 根据 contentType 添加正确的扩展名
     * - 同样内容重复上传会使用相同文件名，支持去重
     *
     * @param fileBytes 文件内容（用于计算哈希）
     * @param contentType MIME 类型（用于获取扩展名）
     */
    private fun generateSafeFileName(fileBytes: ByteArray, contentType: String): String {
        // 计算文件的 MD5 哈希值
        val md5Hash = calculateMD5(fileBytes)
        val extension = getExtensionFromContentType(contentType)
        return "$md5Hash.$extension"
    }

    /**
     * 计算字节数组的 MD5 哈希值
     */
    private fun calculateMD5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(bytes)
        // 将字节数组转换为十六进制字符串
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 从 MIME 类型提取文件扩展名
     */
    private fun getExtensionFromContentType(contentType: String): String = when (contentType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "video/mp4" -> "mp4"
        else -> "bin"
    }
}
