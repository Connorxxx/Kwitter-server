package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.LikeError
import com.connor.domain.model.PostId
import com.connor.domain.model.PostStats
import com.connor.domain.model.UserId
import com.connor.domain.repository.PostRepository
import org.slf4j.LoggerFactory

/**
 * 点赞 Post Use Case
 *
 * 职责：
 * - 调用 Repository 执行点赞操作
 * - 返回更新后的统计信息
 *
 * 注意：实时通知由 Route 层负责，避免在 Use Case 中额外查询数据库
 */
class LikePostUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(LikePostUseCase::class.java)

    suspend operator fun invoke(userId: UserId, postId: PostId): Either<LikeError, PostStats> {
        logger.info("用户点赞Post: userId=${userId.value}, postId=${postId.value}")
        return postRepository.likePost(userId, postId)
    }
}
