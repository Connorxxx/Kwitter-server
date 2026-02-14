package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.left
import com.connor.domain.failure.UserError
import com.connor.domain.model.Follow
import com.connor.domain.model.UserId
import com.connor.domain.repository.UserRepository
import org.slf4j.LoggerFactory

/**
 * 关注用户 UseCase
 *
 * 职责：
 * - 验证业务规则（不能关注自己）
 * - 调用 Repository 创建关注关系
 * - 返回关注记录
 *
 * 设计原理：
 * - 业务规则验证在 UseCase 层
 * - 数据唯一性约束在 Repository 层（数据库 unique index）
 */
class FollowUserUseCase(
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(FollowUserUseCase::class.java)

    suspend operator fun invoke(
        followerId: UserId,
        followingId: UserId
    ): Either<UserError, Follow> {
        logger.info("关注用户: followerId=${followerId.value}, followingId=${followingId.value}")

        // 业务规则：不能关注自己
        if (followerId == followingId) {
            logger.warn("尝试关注自己: userId=${followerId.value}")
            return UserError.CannotFollowSelf.left()
        }

        // 业务规则：拉黑关系中不能关注
        if (userRepository.isBlocked(followerId, followingId)) {
            logger.warn("拉黑关系中尝试关注: followerId=${followerId.value}, followingId=${followingId.value}")
            return UserError.UserBlocked(followingId).left()
        }

        return userRepository.follow(followerId, followingId)
    }
}
