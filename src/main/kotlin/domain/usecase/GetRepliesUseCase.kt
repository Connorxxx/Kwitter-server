package com.connor.domain.usecase

import com.connor.domain.model.PostDetail
import com.connor.domain.model.PostId
import com.connor.domain.repository.PostRepository
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory

/**
 * 获取某个 Post 的所有回复
 *
 * 职责：
 * - 返回回复列表的流式数据
 * - 未来可扩展：排序策略（最新、最热）、嵌套回复树等
 */
class GetRepliesUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(GetRepliesUseCase::class.java)

    operator fun invoke(parentId: PostId, limit: Int = 20, offset: Int = 0): Flow<PostDetail> {
        logger.info("查询回复列表: parentId=${parentId.value}, limit=$limit, offset=$offset")
        return postRepository.findReplies(parentId, limit, offset)
    }
}
