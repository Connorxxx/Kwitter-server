package com.connor.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.MediaError
import com.connor.domain.model.MediaId
import com.connor.domain.model.MediaType
import com.connor.domain.model.MediaUrl
import com.connor.domain.model.UploadedMedia
import com.connor.domain.repository.MediaStorageRepository
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("FileSystemMediaStorageRepository")

/**
 * 文件系统媒体存储实现 - 将媒体文件存储到本地磁盘
 *
 * 设计特点：
 * - 将文件保存到 uploadDir 目录
 * - 生成公开的 URL 路径：/uploads/{fileName}
 * - 支持 GET 请求直接访问（通过 Ktor 的 staticFiles）
 * - 未来可轻松替换为 S3/OSS 实现
 *
 * @param uploadDir 上传目录的绝对或相对路径
 */
class FileSystemMediaStorageRepository(
    private val uploadDir: String
) : MediaStorageRepository {

    init {
        // 启动时确保上传目录存在
        val directory = File(uploadDir)
        if (!directory.exists()) {
            directory.mkdirs()
            logger.info("Created upload directory: ${directory.absolutePath}")
        }
    }

    /**
     * 上传文件到文件系统
     *
     * @param file 文件内容（字节数组）
     * @param fileName 文件名（格式：{MD5哈希}.{扩展名}，由 UseCase 生成）
     * @return Either<MediaError, UploadedMedia> 上传结果或错误
     *
     * 如果文件已存在（同一个哈希值），会返回已存在的文件信息而不是错误
     * （这支持了去重功能 - 同一文件重复上传会复用已存在的文件）
     */
    override suspend fun upload(file: ByteArray, fileName: String): Either<MediaError, UploadedMedia> {
        return try {
            // 验证文件名安全性（防止路径遍历攻击）
            if (!isValidFileName(fileName)) {
                return MediaError.InvalidFileName(fileName).left()
            }

            // 生成文件路径
            val filePath = File(uploadDir, fileName)

            // 如果文件已存在（同样内容重复上传），直接返回已存在的文件信息
            // 这支持了去重功能，同一文件重复上传会复用已存在的文件
            if (filePath.exists()) {
                logger.info("Media file already exists (deduplication): fileName=$fileName, path=${filePath.absolutePath}")
                // 构建返回信息并返回
                val publicUrl = "/${uploadDir.replace('\\', '/')}/$fileName"
                val mediaUrl = MediaUrl.unsafe(publicUrl)
                val mediaType = inferMediaType(fileName)
                val uploaded = UploadedMedia(
                    id = MediaId(fileName),
                    url = mediaUrl,
                    type = mediaType,
                    fileSize = filePath.length(),
                    contentType = getContentTypeFromExtension(fileName),
                    uploadedAt = filePath.lastModified()
                )
                return uploaded.right()
            }

            // 写入新文件
            filePath.writeBytes(file)

            // 生成公开 URL（客户端可通过这个 URL 访问）
            val publicUrl = "/${uploadDir.replace('\\', '/')}/$fileName"
            val mediaUrl = MediaUrl.unsafe(publicUrl)

            // 从文件名推断媒体类型
            val mediaType = inferMediaType(fileName)

            // 构建上传结果
            val uploaded = UploadedMedia(
                id = MediaId(fileName), // 使用文件名作为媒体 ID
                url = mediaUrl,
                type = mediaType,
                fileSize = file.size.toLong(),
                contentType = getContentTypeFromExtension(fileName),
                uploadedAt = System.currentTimeMillis()
            )

            logger.info(
                "Media uploaded successfully (new): fileName=$fileName, size=${file.size}, " +
                "type=$mediaType, path=${filePath.absolutePath}"
            )

            uploaded.right()
        } catch (e: Exception) {
            logger.error("Failed to upload media: fileName=$fileName", e)
            MediaError.UploadFailed(e.message ?: "Unknown error").left()
        }
    }

    /**
     * 删除已上传的媒体文件
     *
     * @param mediaId 媒体 ID（在文件系统实现中就是文件名）
     * @return Either<MediaError, Unit> 删除结果或错误
     */
    override suspend fun delete(mediaId: MediaId): Either<MediaError, Unit> {
        return try {
            val fileName = mediaId.value

            // 验证文件名安全性
            if (!isValidFileName(fileName)) {
                return MediaError.InvalidFileName(fileName).left()
            }

            val filePath = File(uploadDir, fileName)

            if (!filePath.exists()) {
                return MediaError.StorageError("File not found: $fileName").left()
            }

            if (!filePath.isFile) {
                return MediaError.StorageError("Path is not a file: $fileName").left()
            }

            val deleted = filePath.delete()

            if (!deleted) {
                return MediaError.DeleteFailed("Failed to delete file: $fileName").left()
            }

            logger.info("Media deleted successfully: fileName=$fileName")
            Unit.right()
        } catch (e: Exception) {
            logger.error("Failed to delete media: mediaId=${mediaId.value}", e)
            MediaError.DeleteFailed(e.message ?: "Unknown error").left()
        }
    }

    /**
     * 验证文件名的安全性
     *
     * 格式：{32位MD5哈希}.{扩展名}
     * 例如：5d41402abc4b2a76b9719d911017c592.jpg
     *
     * 防护：
     * - 防止路径遍历攻击（不允许 /, \, .. 等）
     * - 验证格式符合预期
     */
    private fun isValidFileName(fileName: String): Boolean {
        // 不允许路径分隔符
        if (fileName.contains("/") || fileName.contains("\\")) {
            return false
        }

        // 不允许 . 开头的隐藏文件或 .. 上级目录
        if (fileName.startsWith(".")) {
            return false
        }

        // 格式：32位十六进制 + 点 + 扩展名
        // 32个 0-9a-f，然后点，然后至少3位扩展名（字母数字）
        return fileName.matches(Regex("[a-f0-9]{32}\\.[a-z0-9]{2,5}"))
    }

    /**
     * 从文件名推断媒体类型
     */
    private fun inferMediaType(fileName: String): MediaType {
        val extension = fileName.substringAfterLast(".", "")
        return when (extension.lowercase()) {
            "jpg", "jpeg", "png", "webp", "gif", "svg" -> MediaType.IMAGE
            "mp4", "avi", "mov", "mkv", "webm" -> MediaType.VIDEO
            else -> MediaType.IMAGE // 默认作为图片
        }
    }

    /**
     * 根据文件扩展名推断 MIME 类型
     */
    private fun getContentTypeFromExtension(fileName: String): String {
        val extension = fileName.substringAfterLast(".", "").lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "avi" -> "video/avi"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }
}
