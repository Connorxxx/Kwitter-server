package com.connor.domain.usecase

import com.connor.domain.model.PostDetail
import com.connor.domain.model.UserId
import com.connor.domain.repository.PostRepository
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory

/**
 * 获取某个用户的所有顶层 Posts（用户主页）
 *
 * 职责：
 * - 返回用户发布的所有顶层 Posts（不包括回复）
 * - 未来可扩展：包含/排除回复、置顶 Post 等
 */
class GetUserPostsUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(GetUserPostsUseCase::class.java)

    operator fun invoke(authorId: UserId, limit: Int = 20, offset: Int = 0): Flow<PostDetail> {
        logger.info("查询用户 Posts: authorId=${authorId.value}, limit=$limit, offset=$offset")
        return postRepository.findByAuthor(authorId, limit, offset)
    }
}
