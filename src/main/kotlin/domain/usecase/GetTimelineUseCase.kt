package com.connor.domain.usecase

import com.connor.domain.model.PostDetail
import com.connor.domain.repository.PostRepository
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory

/**
 * 获取时间线（全站最新 Posts）
 *
 * 职责：
 * - 返回时间线流式数据
 * - 未来可扩展：个性化推荐、过滤敏感内容等
 */
class GetTimelineUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(GetTimelineUseCase::class.java)

    operator fun invoke(limit: Int = 20, offset: Int = 0): Flow<PostDetail> {
        logger.info("查询时间线: limit=$limit, offset=$offset")
        return postRepository.findTimeline(limit, offset)
    }
}
