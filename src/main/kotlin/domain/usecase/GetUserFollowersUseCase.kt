package com.connor.domain.usecase

import com.connor.domain.model.User
import com.connor.domain.model.UserId
import com.connor.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

/**
 * 获取用户粉丝列表 UseCase（关注我的人）
 *
 * 职责：
 * - 查询关注该用户的人
 * - 如果有当前用户，批量查询关注状态（避免 N+1）
 * - 返回用户列表及关注状态
 *
 * 设计原理：
 * - 使用批量查询避免 N+1 问题
 * - Flow 方式返回，支持分页和流式处理
 */
class GetUserFollowersUseCase(
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(GetUserFollowersUseCase::class.java)

    /**
     * 粉丝列表项（包含关注状态）
     */
    data class FollowerItem(
        val user: User,
        val isFollowedByCurrentUser: Boolean? = null // null = 当前用户未认证
    )

    operator fun invoke(
        userId: UserId,
        limit: Int = 20,
        offset: Int = 0,
        currentUserId: UserId? = null
    ): Flow<FollowerItem> = flow {
        logger.info("查询粉丝列表: userId=${userId.value}, limit=$limit, offset=$offset, currentUserId=${currentUserId?.value}")

        // 1. 收集所有粉丝
        val users = mutableListOf<User>()
        userRepository.findFollowers(userId, limit, offset).collect { user ->
            users.add(user)
        }

        if (users.isEmpty()) {
            return@flow
        }

        // 2. 如果有当前用户，批量查询关注状态（1次查询，不是N次）
        val followingIds = if (currentUserId != null) {
            userRepository.batchCheckFollowing(currentUserId, users.map { it.id })
        } else {
            emptySet()
        }

        // 3. 返回结果（关注状态已准备好，O(1) 查找）
        users.forEach { user ->
            emit(
                FollowerItem(
                    user = user,
                    isFollowedByCurrentUser = if (currentUserId != null) user.id in followingIds else null
                )
            )
        }
    }
}
