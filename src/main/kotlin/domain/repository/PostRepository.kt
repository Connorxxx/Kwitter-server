package com.connor.domain.repository

import arrow.core.Either
import com.connor.domain.failure.BookmarkError
import com.connor.domain.failure.LikeError
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
     * 获取用户的所有回复（只包括回复，不包括顶层 Posts）
     * @param limit 分页大小
     * @param offset 偏移量
     * @return Flow<PostDetail> 流式返回，方便分页和性能优化
     */
    fun findRepliesByAuthor(authorId: UserId, limit: Int = 20, offset: Int = 0): Flow<PostDetail>

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

    // ========== Like 相关方法 ==========

    /**
     * 用户点赞Post
     */
    suspend fun likePost(userId: UserId, postId: PostId): Either<LikeError, PostStats>

    /**
     * 用户取消点赞
     */
    suspend fun unlikePost(userId: UserId, postId: PostId): Either<LikeError, PostStats>

    /**
     * 检查用户是否已点赞某Post
     */
    suspend fun isLikedByUser(userId: UserId, postId: PostId): Either<LikeError, Boolean>

    /**
     * 获取用户已点赞的Posts列表（按点赞时间倒序）
     */
    fun findUserLikes(userId: UserId, limit: Int = 20, offset: Int = 0): Flow<PostDetail>

    // ========== Bookmark 相关方法 ==========

    /**
     * 用户收藏Post
     */
    suspend fun bookmarkPost(userId: UserId, postId: PostId): Either<BookmarkError, Unit>

    /**
     * 用户取消收藏
     */
    suspend fun unbookmarkPost(userId: UserId, postId: PostId): Either<BookmarkError, Unit>

    /**
     * 检查用户是否已收藏某Post
     */
    suspend fun isBookmarkedByUser(userId: UserId, postId: PostId): Either<BookmarkError, Boolean>

    /**
     * 获取用户已收藏的Posts列表（按收藏时间倒序）
     */
    fun findUserBookmarks(userId: UserId, limit: Int = 20, offset: Int = 0): Flow<PostDetail>

    // ========== 批量查询交互状态（性能优化，避免N+1） ==========

    /**
     * 批量检查用户是否点赞多个Posts
     * @return Either<LikeError, Set<PostId>> - 成功返回用户已点赞的PostId集合
     *
     * 性能特性：
     * - 恒定数 SQL（一条查询）
     * - 批量 IN 查询替代 N+1
     * - 时间复杂度 O(n + m)，其中 n 是 postIds 数，m 是结果数
     */
    suspend fun batchCheckLiked(userId: UserId, postIds: List<PostId>): Either<LikeError, Set<PostId>>

    /**
     * 批量检查用户是否收藏多个Posts
     * @return Either<BookmarkError, Set<PostId>> - 成功返回用户已收藏的PostId集合
     *
     * 性能特性：
     * - 恒定数 SQL（一条查询）
     * - 批量 IN 查询替代 N+1
     */
    suspend fun batchCheckBookmarked(userId: UserId, postIds: List<PostId>): Either<BookmarkError, Set<PostId>>
}
