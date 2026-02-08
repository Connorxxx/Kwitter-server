package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.LikeError
import com.connor.domain.model.PostId
import com.connor.domain.model.PostStats
import com.connor.domain.model.UserId
import com.connor.domain.repository.PostRepository
import org.slf4j.LoggerFactory

class UnlikePostUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(UnlikePostUseCase::class.java)

    suspend operator fun invoke(userId: UserId, postId: PostId): Either<LikeError, PostStats> {
        logger.info("用户取消点赞Post: userId=${userId.value}, postId=${postId.value}")
        return postRepository.unlikePost(userId, postId)
    }
}
