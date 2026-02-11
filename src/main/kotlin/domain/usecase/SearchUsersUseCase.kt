package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.SearchError
import com.connor.domain.model.UserId
import com.connor.domain.model.UserProfile
import com.connor.domain.model.UserSearchSort
import com.connor.domain.repository.SearchRepository
import com.connor.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory

/**
 * 搜索用户并获取当前用户的关注状态
 *
 * 职责：
 * - 搜索用户
 * - 如果用户已认证，批量查询关注状态（避免 N+1）
 * - 返回完整的搜索结果视图
 *
 * 设计原理：
 * - 业务编排在 UseCase 层，保持 Route 层只做协议转换
 * - 关注状态查询失败时，整体返回错误（强一致性，fail-fast）
 * - 使用 Either 处理所有失败场景
 */
class SearchUsersUseCase(
    private val searchRepository: SearchRepository,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(SearchUsersUseCase::class.java)

    /**
     * 搜索结果项目（包含关注状态）
     */
    data class SearchUserItem(
        val userProfile: UserProfile,
        val isFollowedByCurrentUser: Boolean? = null
    )

    /**
     * 搜索错误类型的包装
     */
    sealed interface SearchUserError {
        data class SearchFailed(val error: SearchError) : SearchUserError
        data class FollowingCheckFailed(val reason: String) : SearchUserError
    }

    operator fun invoke(
        query: String,
        sort: UserSearchSort = UserSearchSort.RELEVANCE,
        limit: Int = 20,
        offset: Int = 0,
        currentUserId: UserId? = null
    ): Flow<Either<SearchUserError, SearchUserItem>> = flow {
        logger.info("搜索用户: query='$query', sort=$sort, limit=$limit, offset=$offset, userId=${currentUserId?.value}")

        // 验证查询长度
        if (query.trim().length < 2) {
            emit(Either.Left(SearchUserError.SearchFailed(SearchError.QueryTooShort(query.trim().length))))
            return@flow
        }

        // 1. 收集搜索结果并先检查错误（避免在 collect/fold lambda 中非局部 return）
        val users = mutableListOf<UserProfile>()
        var searchError: SearchError? = null

        searchRepository.searchUsers(query, sort, limit, offset).toList().forEach { result ->
            when (result) {
                is Either.Left -> if (searchError == null) searchError = result.value
                is Either.Right -> if (searchError == null) users.add(result.value)
            }
        }

        val firstSearchError = searchError
        if (firstSearchError != null) {
            logger.warn("搜索用户失败: query='$query', error=$firstSearchError")
            emit(Either.Left(SearchUserError.SearchFailed(firstSearchError)))
            return@flow
        }

        if (users.isEmpty()) return@flow

        // 2. 如果用户已认证，批量查询关注状态（1 次查询，不是 N 次）
        var followingUserIds: Set<UserId> = emptySet()

        if (currentUserId != null) {
            val userIds = users.map { it.user.id }

            try {
                // 批量查询关注状态
                followingUserIds = userRepository.batchCheckFollowing(currentUserId, userIds)
            } catch (e: Exception) {
                logger.warn("查询关注状态失败: userCount=${userIds.size}, error=${e.message}", e)
                emit(Either.Left(SearchUserError.FollowingCheckFailed(e.message ?: "Unknown error")))
                return@flow  // fail-fast: 立即停止
            }
        }

        // 3. 返回结果（关注状态已准备好，O(1) 查找）
        users.forEach { userProfile ->
            emit(
                Either.Right(
                    SearchUserItem(
                        userProfile = userProfile,
                        isFollowedByCurrentUser = if (currentUserId != null) userProfile.user.id in followingUserIds else null
                    )
                )
            )
        }

        logger.info("搜索用户完成: query='$query', resultCount=${users.size}")
    }
}
