package com.connor.domain.usecase

import com.connor.domain.model.PostDetail
import com.connor.domain.model.UserId
import com.connor.domain.repository.PostRepository
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory

class GetUserLikesUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(GetUserLikesUseCase::class.java)

    operator fun invoke(userId: UserId, limit: Int = 20, offset: Int = 0): Flow<PostDetail> {
        logger.info("获取用户点赞列表: userId=${userId.value}, limit=$limit, offset=$offset")
        return postRepository.findUserLikes(userId, limit, offset)
    }
}
