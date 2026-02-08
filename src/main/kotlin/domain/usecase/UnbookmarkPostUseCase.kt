package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.BookmarkError
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.repository.PostRepository
import org.slf4j.LoggerFactory

class UnbookmarkPostUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(UnbookmarkPostUseCase::class.java)

    suspend operator fun invoke(userId: UserId, postId: PostId): Either<BookmarkError, Unit> {
        logger.info("用户取消收藏Post: userId=${userId.value}, postId=${postId.value}")
        return postRepository.unbookmarkPost(userId, postId)
    }
}
