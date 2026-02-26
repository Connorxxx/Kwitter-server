package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.raise.either
import com.connor.domain.failure.UserError
import com.connor.domain.model.UserId
import com.connor.domain.repository.MediaStorageRepository
import com.connor.domain.repository.UserRepository
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("DeleteAvatarUseCase")

/**
 * 头像删除 UseCase
 *
 * 职责：
 * 1. 获取当前用户
 * 2. 清除 avatarUrl（设为 null）
 * 3. 删除头像文件（best-effort）
 * 4. 幂等：用户没有头像时不报错
 */
class DeleteAvatarUseCase(
    private val userRepository: UserRepository,
    private val storageRepository: MediaStorageRepository
) {
    suspend operator fun invoke(userId: UserId): Either<UserError, Unit> = either {
        // 1. 获取当前用户
        val user = userRepository.findById(userId).bind()
        val avatarUrl = user.avatarUrl

        // 2. 清除 avatarUrl（空字符串作为 sentinel 表示 "set to null"）
        userRepository.updateProfile(userId, avatarUrl = "").bind()

        // 3. 删除头像文件（best-effort）
        if (avatarUrl != null) {
            try {
                val fileName = avatarUrl.substringAfterLast("/")
                storageRepository.delete(fileName)
            } catch (e: Exception) {
                logger.warn("Failed to delete avatar file: $avatarUrl", e)
            }
        }
    }
}
