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
 * 获取回复列表及当前用户的交互状态
 *
 * 职责：
 * - 查询回复列表数据
 * - 如果用户已认证，批量查询交互状态（避免N+1）
 * - 返回完整的回复视图
 *
 * 设计原理：
 * - 参考 GetTimelineWithStatusUseCase 的实现
 * - 交互状态查询失败时，整体返回错误（强一致性）
 * - 使用 Either 处理所有失败场景
 */
class GetRepliesWithStatusUseCase(
    private val postRepository: PostRepository,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(GetRepliesWithStatusUseCase::class.java)

    /**
     * 回复项目（包含交互状态）
     */
    data class ReplyItem(
        val postDetail: PostDetail,
        val isLikedByCurrentUser: Boolean? = null,
        val isBookmarkedByCurrentUser: Boolean? = null
    )

    /**
     * 返回回复错误类型的包装
     */
    sealed interface ReplyError {
        data class LikesCheckFailed(val error: LikeError) : ReplyError
        data class BookmarksCheckFailed(val error: BookmarkError) : ReplyError
    }

    operator fun invoke(
        parentId: PostId,
        limit: Int = 20,
        offset: Int = 0,
        currentUserId: UserId? = null
    ): Flow<Either<ReplyError, ReplyItem>> = flow {
        logger.info("查询回复列表及交互状态: parentId=${parentId.value}, limit=$limit, offset=$offset, userId=${currentUserId?.value}")

        // 1. 收集所有回复 Posts（必须在 flow 块中用 collect）
        val replies = mutableListOf<PostDetail>()
        postRepository.findReplies(parentId, limit, offset).collect { reply ->
            replies.add(reply)
        }

        if (replies.isEmpty()) {
            return@flow
        }

        // 2. 过滤拉黑用户的回复
        val blockedUserIds = currentUserId?.let { userRepository.getBlockedRelationUserIds(it) } ?: emptySet()
        val filteredReplies = if (blockedUserIds.isNotEmpty()) {
            replies.filter { it.post.authorId !in blockedUserIds }
        } else {
            replies
        }

        if (filteredReplies.isEmpty()) {
            return@flow
        }

        // 3. 如果用户已认证，批量查询交互状态（1次查询，不是N次）
        var likedPostIds: Set<PostId> = emptySet()
        var bookmarkedPostIds: Set<PostId> = emptySet()

        if (currentUserId != null) {
            val postIds = filteredReplies.map { it.post.id }

            // 批量查询点赞状态（一次性查询所有reply的状态）
            val likedResult = postRepository.batchCheckLiked(currentUserId, postIds)
            if (likedResult.isLeft()) {
                val error = (likedResult as Either.Left).value
                logger.warn("查询点赞状态失败: replyCount=${postIds.size}, error=$error")
                emit(Either.Left(ReplyError.LikesCheckFailed(error)))
                return@flow  // fail-fast：立即停止，不继续查收藏
            }
            likedPostIds = (likedResult as Either.Right).value

            // 批量查询收藏状态（一次性查询所有reply的状态）
            val bookmarkedResult = postRepository.batchCheckBookmarked(currentUserId, postIds)
            if (bookmarkedResult.isLeft()) {
                val error = (bookmarkedResult as Either.Left).value
                logger.warn("查询收藏状态失败: replyCount=${postIds.size}, error=$error")
                emit(Either.Left(ReplyError.BookmarksCheckFailed(error)))
                return@flow  // fail-fast：立即停止
            }
            bookmarkedPostIds = (bookmarkedResult as Either.Right).value
        }

        // 4. 返回结果（交互状态已准备好，O(1) 查找）
        filteredReplies.forEach { replyDetail ->
            emit(
                Either.Right(
                    ReplyItem(
                        postDetail = replyDetail,
                        isLikedByCurrentUser = if (currentUserId != null) replyDetail.post.id in likedPostIds else null,
                        isBookmarkedByCurrentUser = if (currentUserId != null) replyDetail.post.id in bookmarkedPostIds else null
                    )
                )
            )
        }
    }
}
