package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.right
import arrow.core.left
import com.connor.domain.failure.PostError
import com.connor.domain.model.PostDetail
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.repository.PostRepository
import com.connor.domain.repository.UserRepository
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
    private val postRepository: PostRepository,
    private val userRepository: UserRepository
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
        return postRepository.findDetailById(postId).flatMap { postDetail ->
            // 2. 如果存在拉黑关系，隐藏Post
            if (currentUserId != null && userRepository.isBlocked(currentUserId, postDetail.author.id)) {
                return@flatMap PostError.PostNotFound(postId).left()
            }

            // 3. 如果用户未认证，直接返回详情（不包含交互状态）
            if (currentUserId == null) {
                logger.debug("Post详情查询完成: postId=${postId.value}, 用户未认证")
                PostDetailWithStatus(
                    postDetail = postDetail,
                    isLikedByCurrentUser = null,
                    isBookmarkedByCurrentUser = null
                ).right()
            } else {
                // 3. 用户已认证，查询交互状态（强一致性：失败则整体失败）
                postRepository.isLikedByUser(currentUserId, postId).fold(
                    ifLeft = { likeError ->
                        // 查询失败，返回错误
                        PostError.InteractionStateQueryFailed("Failed to check like status: $likeError").left()
                    },
                    ifRight = { isLiked ->
                        // Like查询成功，继续查询Bookmark
                        postRepository.isBookmarkedByUser(currentUserId, postId).fold(
                            ifLeft = { bookmarkError ->
                                // Bookmark查询失败，返回错误
                                PostError.InteractionStateQueryFailed("Failed to check bookmark status: $bookmarkError").left()
                            },
                            ifRight = { isBookmarked ->
                                // 两个查询都成功，构建结果
                                logger.debug(
                                    "Post详情查询完成: postId=${postId.value}, " +
                                    "isLiked=$isLiked, isBookmarked=$isBookmarked"
                                )
                                PostDetailWithStatus(
                                    postDetail = postDetail,
                                    isLikedByCurrentUser = isLiked,
                                    isBookmarkedByCurrentUser = isBookmarked
                                ).right()
                            }
                        )
                    }
                )
            }
        }
    }
}
