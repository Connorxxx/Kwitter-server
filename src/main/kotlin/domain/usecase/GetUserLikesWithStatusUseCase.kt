package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.BookmarkError
import com.connor.domain.failure.LikeError
import com.connor.domain.model.PostDetail
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.repository.PostRepository
import com.connor.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

/**
 * 获取用户点赞列表及当前用户的交互状态
 *
 * 职责：
 * - 查询用户点赞的Posts
 * - 如果有认证用户，批量查询交互状态（避免N+1）
 * - 返回完整的视图
 *
 * 设计原理：
 * - 参考 GetTimelineWithStatusUseCase 的实现
 * - 交互状态查询失败时，整体返回错误（强一致性）
 * - 使用 Either 处理所有失败场景
 * - 先校验目标用户是否存在，避免返回空列表的语义混淆
 */
class GetUserLikesWithStatusUseCase(
    private val postRepository: PostRepository,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(GetUserLikesWithStatusUseCase::class.java)

    /**
     * 点赞项目（包含交互状态）
     */
    data class LikedPostItem(
        val postDetail: PostDetail,
        val isLikedByCurrentUser: Boolean? = null,
        val isBookmarkedByCurrentUser: Boolean? = null
    )

    /**
     * 返回错误类型的包装
     */
    sealed interface UserLikesError {
        data class UserNotFound(val userId: UserId) : UserLikesError
        data class LikesCheckFailed(val error: LikeError) : UserLikesError
        data class BookmarksCheckFailed(val error: BookmarkError) : UserLikesError
    }

    operator fun invoke(
        userId: UserId,
        limit: Int = 20,
        offset: Int = 0,
        currentUserId: UserId? = null
    ): Flow<Either<UserLikesError, LikedPostItem>> = flow {
        logger.info("查询用户点赞列表及交互状态: userId=${userId.value}, limit=$limit, offset=$offset, currentUserId=${currentUserId?.value}")

        // 1. 先验证目标用户是否存在
        val userExistsResult = userRepository.findById(userId)
        if (userExistsResult.isLeft()) {
            logger.warn("目标用户不存在: userId=${userId.value}")
            emit(Either.Left(UserLikesError.UserNotFound(userId)))
            return@flow
        }

        // 2. 收集所有点赞的Posts（必须在 flow 块中用 collect）
        val posts = mutableListOf<PostDetail>()
        postRepository.findUserLikes(userId, limit, offset).collect { post ->
            posts.add(post)
        }

        if (posts.isEmpty()) {
            return@flow
        }

        // 2. 如果用户已认证，批量查询交互状态（1次查询，不是N次）
        var likedPostIds: Set<PostId> = emptySet()
        var bookmarkedPostIds: Set<PostId> = emptySet()

        if (currentUserId != null) {
            val postIds = posts.map { it.post.id }

            // 批量查询点赞状态（一次性查询所有post的状态）
            val likedResult = postRepository.batchCheckLiked(currentUserId, postIds)
            if (likedResult.isLeft()) {
                val error = (likedResult as Either.Left).value
                logger.warn("查询点赞状态失败: postCount=${postIds.size}, error=$error")
                emit(Either.Left(UserLikesError.LikesCheckFailed(error)))
                return@flow  // fail-fast：立即停止，不继续查收藏
            }
            likedPostIds = (likedResult as Either.Right).value

            // 批量查询收藏状态（一次性查询所有post的状态）
            val bookmarkedResult = postRepository.batchCheckBookmarked(currentUserId, postIds)
            if (bookmarkedResult.isLeft()) {
                val error = (bookmarkedResult as Either.Left).value
                logger.warn("查询收藏状态失败: postCount=${postIds.size}, error=$error")
                emit(Either.Left(UserLikesError.BookmarksCheckFailed(error)))
                return@flow  // fail-fast：立即停止
            }
            bookmarkedPostIds = (bookmarkedResult as Either.Right).value
        }

        // 3. 返回结果（交互状态已准备好，O(1) 查找）
        posts.forEach { postDetail ->
            emit(
                Either.Right(
                    LikedPostItem(
                        postDetail = postDetail,
                        isLikedByCurrentUser = if (currentUserId != null) postDetail.post.id in likedPostIds else null,
                        isBookmarkedByCurrentUser = if (currentUserId != null) postDetail.post.id in bookmarkedPostIds else null
                    )
                )
            )
        }
    }
}
