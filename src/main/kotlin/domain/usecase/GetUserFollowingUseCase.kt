package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.UserError
import com.connor.domain.model.User
import com.connor.domain.model.UserId
import com.connor.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

/**
 * 获取用户关注列表 UseCase（我关注的人）
 *
 * 职责：
 * - 查询用户关注的人
 * - 如果有当前用户，批量查询关注状态（避免 N+1）
 * - 返回用户列表及关注状态
 *
 * 设计原理：
 * - 使用批量查询避免 N+1 问题
 * - Flow 方式返回，支持分页和流式处理
 * - 先校验目标用户是否存在，避免返回空列表的语义混淆
 */
class GetUserFollowingUseCase(
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(GetUserFollowingUseCase::class.java)

    /**
     * 关注列表项（包含关注状态）
     */
    data class FollowingItem(
        val user: User,
        val isFollowedByCurrentUser: Boolean? = null // null = 当前用户未认证
    )

    operator fun invoke(
        userId: UserId,
        limit: Int = 20,
        offset: Int = 0,
        currentUserId: UserId? = null
    ): Flow<Either<UserError, FollowingItem>> = flow {
        logger.info("查询关注列表: userId=${userId.value}, limit=$limit, offset=$offset, currentUserId=${currentUserId?.value}")

        // 1. 先验证目标用户是否存在
        val userExistsResult = userRepository.findById(userId)
        if (userExistsResult.isLeft()) {
            logger.warn("目标用户不存在: userId=${userId.value}")
            emit(Either.Left(UserError.UserNotFound(userId)))
            return@flow
        }

        // 2. 收集所有关注的用户
        val users = mutableListOf<User>()
        userRepository.findFollowing(userId, limit, offset).collect { user ->
            users.add(user)
        }

        if (users.isEmpty()) {
            return@flow
        }

        // 3. 如果有当前用户，批量查询关注状态（1次查询，不是N次）
        val followingIds = if (currentUserId != null) {
            userRepository.batchCheckFollowing(currentUserId, users.map { it.id })
        } else {
            emptySet()
        }

        // 4. 返回结果（关注状态已准备好，O(1) 查找）
        users.forEach { user ->
            emit(
                Either.Right(
                    FollowingItem(
                        user = user,
                        isFollowedByCurrentUser = if (currentUserId != null) user.id in followingIds else null
                    )
                )
            )
        }
    }
}
