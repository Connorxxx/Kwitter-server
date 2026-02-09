package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.left
import com.connor.domain.failure.UserError
import com.connor.domain.model.UserId
import com.connor.domain.repository.UserRepository
import org.slf4j.LoggerFactory

/**
 * 取消关注 UseCase
 *
 * 职责：
 * - 验证业务规则（不能取消关注自己）
 * - 调用 Repository 删除关注关系
 */
class UnfollowUserUseCase(
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(UnfollowUserUseCase::class.java)

    suspend operator fun invoke(
        followerId: UserId,
        followingId: UserId
    ): Either<UserError, Unit> {
        logger.info("取消关注: followerId=${followerId.value}, followingId=${followingId.value}")

        // 业务规则：不能取消关注自己
        if (followerId == followingId) {
            logger.warn("尝试取消关注自己: userId=${followerId.value}")
            return UserError.CannotFollowSelf.left()
        }

        return userRepository.unfollow(followerId, followingId)
    }
}
