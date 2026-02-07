package com.connor.domain.repository

import arrow.core.Either
import com.connor.domain.failure.PostError
import com.connor.domain.model.Post
import com.connor.domain.model.PostDetail
import com.connor.domain.model.PostId
import com.connor.domain.model.PostStats
import com.connor.domain.model.UserId
import kotlinx.coroutines.flow.Flow

/**
 * Post Repository - Domain 层的端口（Port）
 *
 * 设计原则：
 * - 接口在 domain 层，实现在 infrastructure 层（依赖倒置）
 * - 返回 Either 处理业务错误（不抛异常）
 * - 使用 Flow 处理流式数据（分页、实时更新）
 */
interface PostRepository {
    /**
     * 创建新 Post
     * @return Either.Left(PostError) 如果验证失败或父 Post 不存在
     */
    suspend fun create(post: Post): Either<PostError, Post>

    /**
     * 根据 ID 查找 Post
     * @return Either.Left(PostError.PostNotFound) 如果不存在
     */
    suspend fun findById(postId: PostId): Either<PostError, Post>

    /**
     * 查找 Post 详情（包含作者信息、统计数据、父 Post）
     * @return Either.Left(PostError.PostNotFound) 如果不存在
     */
    suspend fun findDetailById(postId: PostId): Either<PostError, PostDetail>

    /**
     * 获取用户的所有顶层 Posts（不包括回复）
     * @param limit 分页大小
     * @param offset 偏移量
     * @return Flow<PostDetail> 流式返回，方便分页和性能优化
     */
    fun findByAuthor(authorId: UserId, limit: Int = 20, offset: Int = 0): Flow<PostDetail>

    /**
     * 获取某个 Post 的所有回复
     * @param limit 分页大小
     * @param offset 偏移量
     * @return Flow<PostDetail> 流式返回
     */
    fun findReplies(parentId: PostId, limit: Int = 20, offset: Int = 0): Flow<PostDetail>

    /**
     * 获取时间线（全站最新 Posts，不包括回复）
     * 实际项目中可能需要推荐算法，这里简化为时间倒序
     *
     * @param limit 分页大小
     * @param offset 偏移量
     * @return Flow<PostDetail> 流式返回
     */
    fun findTimeline(limit: Int = 20, offset: Int = 0): Flow<PostDetail>

    /**
     * 删除 Post（软删除或硬删除由实现决定）
     * @return Either.Left(PostError.PostNotFound) 如果不存在
     */
    suspend fun delete(postId: PostId): Either<PostError, Unit>

    /**
     * 更新 Post 统计信息（回复数、点赞数等）
     * 这个方法可能由其他聚合根（如 Like、Reply）触发
     */
    suspend fun updateStats(postId: PostId, stats: PostStats): Either<PostError, Unit>
}
