package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.raise.either
import com.connor.domain.failure.UserError
import com.connor.domain.model.AvatarConfig
import com.connor.domain.model.UserId
import com.connor.domain.repository.MediaStorageRepository
import com.connor.domain.repository.UserRepository
import org.slf4j.LoggerFactory
import java.security.MessageDigest

private val logger = LoggerFactory.getLogger("UploadAvatarUseCase")

/**
 * 头像上传 UseCase
 *
 * 职责：
 * 1. 验证文件类型（仅图片）和大小（<=2MB）
 * 2. 生成 MD5 安全文件名
 * 3. 上传到 avatar 专用存储
 * 4. 更新用户 avatarUrl
 * 5. 删除旧头像（best-effort）
 */
class UploadAvatarUseCase(
    private val userRepository: UserRepository,
    private val storageRepository: MediaStorageRepository,
    private val config: AvatarConfig
) {
    suspend operator fun invoke(
        userId: UserId,
        contentType: String,
        fileBytes: ByteArray
    ): Either<UserError, String> = either {
        // 1. 验证文件类型
        if (contentType !in config.allowedTypes) {
            raise(UserError.InvalidAvatarType(contentType, config.allowedTypes))
        }

        // 2. 验证文件大小
        val fileSize = fileBytes.size.toLong()
        if (fileSize > config.maxFileSize) {
            raise(UserError.AvatarTooLarge(fileSize, config.maxFileSize))
        }

        // 3. 生成安全文件名
        val safeFileName = generateSafeFileName(fileBytes, contentType)

        // 4. 上传文件
        val uploaded = storageRepository.upload(fileBytes, safeFileName)
            .mapLeft { UserError.AvatarUploadFailed(it.toString()) }
            .bind()

        val newAvatarUrl = uploaded.url.value

        // 5. 获取当前用户（拿到旧头像 URL）
        val user = userRepository.findById(userId).bind()
        val oldAvatarUrl = user.avatarUrl

        // 6. 更新 avatarUrl
        userRepository.updateProfile(userId, avatarUrl = newAvatarUrl).bind()

        // 7. 删除旧头像（best-effort）
        if (oldAvatarUrl != null && oldAvatarUrl != newAvatarUrl) {
            try {
                val oldFileName = oldAvatarUrl.substringAfterLast("/")
                storageRepository.delete(oldFileName)
            } catch (e: Exception) {
                logger.warn("Failed to delete old avatar: $oldAvatarUrl", e)
            }
        }

        newAvatarUrl
    }

    private fun generateSafeFileName(fileBytes: ByteArray, contentType: String): String {
        val md5Hash = calculateMD5(fileBytes)
        val extension = getExtensionFromContentType(contentType)
        return "$md5Hash.$extension"
    }

    private fun calculateMD5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun getExtensionFromContentType(contentType: String): String = when (contentType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "bin"
    }
}
