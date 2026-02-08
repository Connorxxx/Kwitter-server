package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.BookmarkError
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.repository.PostRepository
import org.slf4j.LoggerFactory

class BookmarkPostUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(BookmarkPostUseCase::class.java)

    suspend operator fun invoke(userId: UserId, postId: PostId): Either<BookmarkError, Unit> {
        logger.info("用户收藏Post: userId=${userId.value}, postId=${postId.value}")
        return postRepository.bookmarkPost(userId, postId)
    }
}
