package com.connor.domain.repository

import arrow.core.Either
import com.connor.domain.failure.SearchError
import com.connor.domain.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Search Repository - Domain 层的端口（Port）
 *
 * 设计原则：
 * - 接口在 domain 层，实现在 infrastructure 层（依赖倒置）
 * - 使用 PostgreSQL Full-Text Search（tsvector + GIN 索引）
 * - 返回 Either 处理搜索错误
 * - 使用 Flow 处理流式数据
 * - 不包含交互状态（由 UseCase 层批量查询）
 */
interface SearchRepository {
    /**
     * 搜索 Posts（不包括回复）
     *
     * @param query 搜索关键词（最少 2 个字符）
     * @param sort 排序方式（RELEVANCE 或 RECENT）
     * @param limit 分页大小（建议使用 limit + 1 拉取法判断 hasMore）
     * @param offset 偏移量
     * @return Flow<Either<SearchError, PostDetail>> 流式返回搜索结果
     */
    fun searchPosts(
        query: String,
        sort: PostSearchSort = PostSearchSort.RELEVANCE,
        limit: Int = 20,
        offset: Int = 0
    ): Flow<Either<SearchError, PostDetail>>

    /**
     * 搜索 Replies（只返回回复，parentId != null）
     *
     * @param query 搜索关键词（最少 2 个字符）
     * @param limit 分页大小
     * @param offset 偏移量
     * @return Flow<Either<SearchError, PostDetail>> 流式返回搜索结果
     */
    fun searchReplies(
        query: String,
        limit: Int = 20,
        offset: Int = 0
    ): Flow<Either<SearchError, PostDetail>>

    /**
     * 搜索用户
     *
     * @param query 搜索关键词（最少 2 个字符）
     * @param sort 排序方式（默认 RELEVANCE）
     * @param limit 分页大小
     * @param offset 偏移量
     * @return Flow<Either<SearchError, UserProfile>> 流式返回搜索结果
     *
     * 加权搜索：
     * - username (权重A) > displayName (权重B) > bio (权重C)
     */
    fun searchUsers(
        query: String,
        sort: UserSearchSort = UserSearchSort.RELEVANCE,
        limit: Int = 20,
        offset: Int = 0
    ): Flow<Either<SearchError, UserProfile>>
}
