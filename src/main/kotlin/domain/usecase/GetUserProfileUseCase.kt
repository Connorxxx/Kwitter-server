package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.UserError
import com.connor.domain.model.UserId
import com.connor.domain.model.UserProfile
import com.connor.domain.model.Username
import com.connor.domain.repository.UserRepository
import org.slf4j.LoggerFactory

/**
 * 获取用户资料 UseCase
 *
 * 职责：
 * - 通过 UserId 或 Username 查询用户资料
 * - 返回用户基本信息 + 统计数据（Following/Followers/Posts 数量）
 * - 如果有当前用户，附加关注状态
 *
 * 设计原理：
 * - 单一职责：只负责查询，不涉及修改
 * - Repository 已经聚合了统计信息，UseCase 只需要添加关注状态
 */
class GetUserProfileUseCase(
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(GetUserProfileUseCase::class.java)

    /**
     * 用户资料视图（包含关注状态和拉黑状态）
     */
    data class ProfileView(
        val profile: UserProfile,
        val isFollowedByCurrentUser: Boolean? = null, // null = 当前用户未认证
        val isBlockedByCurrentUser: Boolean? = null // null = 当前用户未认证
    )

    /**
     * 通过 UserId 查询
     */
    suspend operator fun invoke(
        userId: UserId,
        currentUserId: UserId? = null
    ): Either<UserError, ProfileView> {
        logger.info("查询用户资料: userId=${userId.value}, currentUserId=${currentUserId?.value}")

        return userRepository.findProfile(userId).map { profile ->
            val isFollowing = if (currentUserId != null && currentUserId != userId) {
                userRepository.isFollowing(currentUserId, userId)
            } else {
                null
            }

            val isBlocked = if (currentUserId != null && currentUserId != userId) {
                userRepository.isBlocked(currentUserId, userId)
            } else {
                null
            }

            ProfileView(
                profile = profile,
                isFollowedByCurrentUser = isFollowing,
                isBlockedByCurrentUser = isBlocked
            )
        }
    }

    /**
     * 通过 Username 查询
     */
    suspend fun byUsername(
        username: Username,
        currentUserId: UserId? = null
    ): Either<UserError, ProfileView> {
        logger.info("查询用户资料: username=${username.value}, currentUserId=${currentUserId?.value}")

        return userRepository.findProfileByUsername(username).map { profile ->
            val isFollowing = if (currentUserId != null && currentUserId != profile.user.id) {
                userRepository.isFollowing(currentUserId, profile.user.id)
            } else {
                null
            }

            val isBlocked = if (currentUserId != null && currentUserId != profile.user.id) {
                userRepository.isBlocked(currentUserId, profile.user.id)
            } else {
                null
            }

            ProfileView(
                profile = profile,
                isFollowedByCurrentUser = isFollowing,
                isBlockedByCurrentUser = isBlocked
            )
        }
    }
}
