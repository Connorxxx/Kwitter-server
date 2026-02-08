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
 * 获取时间线及当前用户的交互状态
 *
 * 职责：
 * - 查询时间线数据
 * - 如果用户已认证，批量查询交互状态（避免N+1）
 * - 返回完整的时间线视图
 *
 * 设计原理：
 * - 业务编排在 UseCase 层，保持 Route 层只做协议转换
 * - 交互状态查询失败时，整体返回错误（强一致性）
 * - 使用 Either 处理所有失败场景
 */
class GetTimelineWithStatusUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(GetTimelineWithStatusUseCase::class.java)

    /**
     * 时间线项目（包含交互状态）
     */
    data class TimelineItem(
        val postDetail: PostDetail,
        val isLikedByCurrentUser: Boolean? = null,
        val isBookmarkedByCurrentUser: Boolean? = null
    )

    /**
     * 返回时间线错误类型的包装
     */
    sealed interface TimelineError {
        data class LikesCheckFailed(val error: LikeError) : TimelineError
        data class BookmarksCheckFailed(val error: BookmarkError) : TimelineError
    }

    operator fun invoke(
        limit: Int = 20,
        offset: Int = 0,
        currentUserId: UserId? = null
    ): Flow<Either<TimelineError, TimelineItem>> = flow {
        logger.info("查询时间线及交互状态: limit=$limit, offset=$offset, userId=${currentUserId?.value}")

        // 1. 获取时间线
        val posts = postRepository.findTimeline(limit, offset)

        posts.collect { postDetail ->
            // 2. 如果用户已认证，查询交互状态
            if (currentUserId != null) {
                // 批量查询点赞状态
                val likedResult = postRepository.batchCheckLiked(
                    currentUserId,
                    listOf(postDetail.post.id)
                )

                // 处理点赞查询结果
                val likedPostIds = likedResult.fold(
                    ifLeft = { error ->
                        logger.warn("查询点赞状态失败: postId=${postDetail.post.id.value}, error=$error")
                        emit(Either.Left(TimelineError.LikesCheckFailed(error)))
                        return@collect
                    },
                    ifRight = { it }
                )

                // 批量查询收藏状态
                val bookmarkedResult = postRepository.batchCheckBookmarked(
                    currentUserId,
                    listOf(postDetail.post.id)
                )

                // 处理收藏查询结果
                val bookmarkedPostIds = bookmarkedResult.fold(
                    ifLeft = { error ->
                        logger.warn("查询收藏状态失败: postId=${postDetail.post.id.value}, error=$error")
                        emit(Either.Left(TimelineError.BookmarksCheckFailed(error)))
                        return@collect
                    },
                    ifRight = { it }
                )

                val isLiked = postDetail.post.id in likedPostIds
                val isBookmarked = postDetail.post.id in bookmarkedPostIds

                emit(
                    Either.Right(
                        TimelineItem(
                            postDetail = postDetail,
                            isLikedByCurrentUser = isLiked,
                            isBookmarkedByCurrentUser = isBookmarked
                        )
                    )
                )
            } else {
                // 未认证用户，交互状态为 null
                emit(
                    Either.Right(
                        TimelineItem(
                            postDetail = postDetail,
                            isLikedByCurrentUser = null,
                            isBookmarkedByCurrentUser = null
                        )
                    )
                )
            }
        }
    }
}
