package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.PostError
import com.connor.domain.model.PostDetail
import com.connor.domain.model.PostId
import com.connor.domain.repository.PostRepository
import org.slf4j.LoggerFactory

/**
 * 获取 Post 详情的业务逻辑
 *
 * 职责：
 * - 查询 Post 详情（包含作者、统计、父 Post）
 * - 未来可扩展：记录浏览量、检查权限等
 */
class GetPostUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(GetPostUseCase::class.java)

    suspend operator fun invoke(postId: PostId): Either<PostError, PostDetail> {
        logger.info("查询 Post 详情: postId=${postId.value}")

        return postRepository.findDetailById(postId).onLeft { error ->
            logger.warn("Post 不存在: postId=${postId.value}")
        }
    }
}
