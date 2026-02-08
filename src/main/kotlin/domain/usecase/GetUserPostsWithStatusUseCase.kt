package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.BookmarkError
import com.connor.domain.failure.LikeError
import com.connor.domain.model.PostDetail
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.repository.PostRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

/**
 * 获取用户Posts列表及当前用户的交互状态
 *
 * 职责：
 * - 查询用户发布的Posts
 * - 如果有认证用户，批量查询交互状态（避免N+1）
 * - 返回完整的Posts视图
 *
 * 设计原理：
 * - 参考 GetTimelineWithStatusUseCase 的实现
 * - 交互状态查询失败时，整体返回错误（强一致性）
 * - 使用 Either 处理所有失败场景
 */
class GetUserPostsWithStatusUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(GetUserPostsWithStatusUseCase::class.java)

    /**
     * 用户Post项目（包含交互状态）
     */
    data class UserPostItem(
        val postDetail: PostDetail,
        val isLikedByCurrentUser: Boolean? = null,
        val isBookmarkedByCurrentUser: Boolean? = null
    )

    /**
     * 返回错误类型的包装
     */
    sealed interface UserPostError {
        data class LikesCheckFailed(val error: LikeError) : UserPostError
        data class BookmarksCheckFailed(val error: BookmarkError) : UserPostError
    }

    operator fun invoke(
        authorId: UserId,
        limit: Int = 20,
        offset: Int = 0,
        currentUserId: UserId? = null
    ): Flow<Either<UserPostError, UserPostItem>> = flow {
        logger.info("查询用户Posts及交互状态: authorId=${authorId.value}, limit=$limit, offset=$offset, currentUserId=${currentUserId?.value}")

        // 1. 收集所有用户Posts（必须在 flow 块中用 collect）
        val posts = mutableListOf<PostDetail>()
        postRepository.findByAuthor(authorId, limit, offset).collect { post ->
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
                emit(Either.Left(UserPostError.LikesCheckFailed(error)))
                return@flow  // fail-fast：立即停止，不继续查收藏
            }
            likedPostIds = (likedResult as Either.Right).value

            // 批量查询收藏状态（一次性查询所有post的状态）
            val bookmarkedResult = postRepository.batchCheckBookmarked(currentUserId, postIds)
            if (bookmarkedResult.isLeft()) {
                val error = (bookmarkedResult as Either.Left).value
                logger.warn("查询收藏状态失败: postCount=${postIds.size}, error=$error")
                emit(Either.Left(UserPostError.BookmarksCheckFailed(error)))
                return@flow  // fail-fast：立即停止
            }
            bookmarkedPostIds = (bookmarkedResult as Either.Right).value
        }

        // 3. 返回结果（交互状态已准备好，O(1) 查找）
        posts.forEach { postDetail ->
            emit(
                Either.Right(
                    UserPostItem(
                        postDetail = postDetail,
                        isLikedByCurrentUser = if (currentUserId != null) postDetail.post.id in likedPostIds else null,
                        isBookmarkedByCurrentUser = if (currentUserId != null) postDetail.post.id in bookmarkedPostIds else null
                    )
                )
            )
        }
    }
}
