package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.raise.either
import com.connor.domain.failure.PostError
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.repository.PostRepository
import org.slf4j.LoggerFactory

class DeletePostUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(DeletePostUseCase::class.java)

    suspend operator fun invoke(postId: PostId, requestingUserId: UserId): Either<PostError, Unit> = either {
        logger.info("删除 Post 请求: postId=${postId.value}, requestingUserId=${requestingUserId.value}")

        val post = postRepository.findById(postId).bind()

        if (post.authorId != requestingUserId) {
            logger.warn("无权删除: postId=${postId.value}, authorId=${post.authorId.value}, requestingUserId=${requestingUserId.value}")
            raise(PostError.Unauthorized(requestingUserId.value.toString(), "delete"))
        }

        postRepository.delete(postId).bind()

        logger.info("Post 删除成功: postId=${postId.value}")
    }
}
