package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.PostError
import com.connor.domain.model.PostDetail
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.repository.PostRepository
import org.slf4j.LoggerFactory

/**
 * 获取Post详情及当前用户的交互状态
 *
 * 这个UseCase封装了"获取详情+检查点赞+检查收藏"的完整业务流程
 * 而不是让Route层直接调用Repository查询交互状态
 *
 * 设计原理：Route -> UseCase -> Repository（遵循Hexagonal Architecture）
 */
class GetPostDetailWithStatusUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(GetPostDetailWithStatusUseCase::class.java)

    data class PostDetailWithStatus(
        val postDetail: PostDetail,
        val isLikedByCurrentUser: Boolean? = null,
        val isBookmarkedByCurrentUser: Boolean? = null
    )

    suspend operator fun invoke(
        postId: PostId,
        currentUserId: UserId? = null
    ): Either<PostError, PostDetailWithStatus> {
        logger.debug("查询Post详情及交互状态: postId=${postId.value}, userId=${currentUserId?.value}")

        // 1. 先查询Post详情
        return postRepository.findDetailById(postId).map { postDetail ->
            // 2. 如果用户已认证，查询交互状态
            val isLiked = currentUserId?.let {
                postRepository.isLikedByUser(it, postId).getOrNull()
            }
            val isBookmarked = currentUserId?.let {
                postRepository.isBookmarkedByUser(it, postId).getOrNull()
            }

            logger.debug(
                "Post详情查询完成: postId=${postId.value}, " +
                "isLiked=$isLiked, isBookmarked=$isBookmarked"
            )

            PostDetailWithStatus(
                postDetail = postDetail,
                isLikedByCurrentUser = isLiked,
                isBookmarkedByCurrentUser = isBookmarked
            )
        }
    }
}
