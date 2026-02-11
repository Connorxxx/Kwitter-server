package com.connor.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.data.db.dbQuery
import com.connor.data.db.mapping.toDomain
import com.connor.data.db.mapping.toMediaAttachment
import com.connor.data.db.mapping.toPost
import com.connor.data.db.mapping.toPostStats
import com.connor.data.db.schema.MediaTable
import com.connor.data.db.schema.PostsTable
import com.connor.data.db.schema.UsersTable
import com.connor.domain.failure.SearchError
import com.connor.domain.model.*
import com.connor.domain.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory

/**
 * SearchRepository 实现 - 使用 PostgreSQL Full-Text Search
 *
 * 技术细节：
 * - 使用 tsvector + GIN 索引实现高性能全文搜索
 * - 使用 to_tsquery 转换查询为 tsquery 格式
 * - 使用 ts_rank 计算相关性分数
 * - 使用 @@ 运算符进行全文匹配
 * - 批量加载 media 避免 N+1 查询
 * - 复用 ExposedUserRepository.calculateUserStats()
 */
class ExposedSearchRepository(
    private val exposedUserRepository: ExposedUserRepository
) : SearchRepository {
    private val logger = LoggerFactory.getLogger(ExposedSearchRepository::class.java)

    override fun searchPosts(
        query: String,
        sort: PostSearchSort,
        limit: Int,
        offset: Int
    ): Flow<Either<SearchError, PostDetail>> = flow {
        try {
            val trimmedQuery = query.trim()
            if (trimmedQuery.length < 2) {
                emit(Either.Left(SearchError.QueryTooShort(trimmedQuery.length)))
                return@flow
            }

            val details = dbQuery {
                logger.debug("搜索 Posts: query='$trimmedQuery', sort=$sort, limit=$limit, offset=$offset")

                // 转换查询为 tsquery 格式（空格替换为 &，表示 AND 关系）
                val tsquery = trimmedQuery.replace(Regex("\\s+"), " & ")

                // 构建 FTS 查询
                val baseQuery = (PostsTable innerJoin UsersTable)
                    .select(PostsTable.columns + UsersTable.columns)
                    .where {
                        // 只搜索顶层 Posts（parentId IS NULL）
                        (PostsTable.parentId.isNull()) and
                        // 全文搜索匹配
                        CustomOperator<Boolean>(
                            "@@",
                            PostsTable.searchVector(),
                            toTsQuery(tsquery)
                        )
                    }

                // 根据排序方式构建查询
                val orderedQuery = when (sort) {
                    PostSearchSort.RELEVANCE -> {
                        // 按相关性排序（ts_rank DESC）
                        baseQuery.orderBy(tsRank(PostsTable.searchVector(), toTsQuery(tsquery)) to SortOrder.DESC)
                    }
                    PostSearchSort.RECENT -> {
                        // 按时间倒序
                        baseQuery.orderBy(PostsTable.createdAt to SortOrder.DESC)
                    }
                }

                val rows = orderedQuery.limit(limit + 1).offset(offset.toLong()).toList()

                // 批量加载 media
                val postIds = rows.map { PostId(it[PostsTable.id]) }
                val mediaMap = batchLoadMedia(postIds)

                rows.map { row -> row.toPostDetailWithMedia(mediaMap) }
            }

            details.forEach { detail ->
                emit(Either.Right(detail))
            }

            logger.info("搜索 Posts 成功: query='$trimmedQuery', resultCount=${details.size}")

        } catch (e: Exception) {
            logger.error("搜索 Posts 失败: query='$query', error=${e.message}", e)
            emit(Either.Left(SearchError.DatabaseError(e.message ?: "Unknown error")))
        }
    }

    override fun searchReplies(
        query: String,
        limit: Int,
        offset: Int
    ): Flow<Either<SearchError, PostDetail>> = flow {
        try {
            val trimmedQuery = query.trim()
            if (trimmedQuery.length < 2) {
                emit(Either.Left(SearchError.QueryTooShort(trimmedQuery.length)))
                return@flow
            }

            val details = dbQuery {
                logger.debug("搜索 Replies: query='$trimmedQuery', limit=$limit, offset=$offset")

                // 转换查询为 tsquery 格式
                val tsquery = trimmedQuery.replace(Regex("\\s+"), " & ")

                // 构建 FTS 查询（只搜索回复）
                val rows = (PostsTable innerJoin UsersTable)
                    .select(PostsTable.columns + UsersTable.columns)
                    .where {
                        // 只搜索回复（parentId IS NOT NULL）
                        (PostsTable.parentId.isNotNull()) and
                        // 全文搜索匹配
                        CustomOperator<Boolean>(
                            "@@",
                            PostsTable.searchVector(),
                            toTsQuery(tsquery)
                        )
                    }
                    .orderBy(tsRank(PostsTable.searchVector(), toTsQuery(tsquery)) to SortOrder.DESC)
                    .limit(limit + 1).offset(offset.toLong())
                    .toList()

                // 批量加载 media
                val postIds = rows.map { PostId(it[PostsTable.id]) }
                val mediaMap = batchLoadMedia(postIds)

                rows.map { row -> row.toPostDetailWithMedia(mediaMap) }
            }

            details.forEach { detail ->
                emit(Either.Right(detail))
            }

            logger.info("搜索 Replies 成功: query='$trimmedQuery', resultCount=${details.size}")

        } catch (e: Exception) {
            logger.error("搜索 Replies 失败: query='$query', error=${e.message}", e)
            emit(Either.Left(SearchError.DatabaseError(e.message ?: "Unknown error")))
        }
    }

    override fun searchUsers(
        query: String,
        sort: UserSearchSort,
        limit: Int,
        offset: Int
    ): Flow<Either<SearchError, UserProfile>> = flow {
        try {
            val trimmedQuery = query.trim()
            if (trimmedQuery.length < 2) {
                emit(Either.Left(SearchError.QueryTooShort(trimmedQuery.length)))
                return@flow
            }

            val profiles = dbQuery {
                logger.debug("搜索用户: query='$trimmedQuery', limit=$limit, offset=$offset")

                // 转换查询为 tsquery 格式
                val tsquery = trimmedQuery.replace(Regex("\\s+"), " & ")

                // 构建 FTS 查询（加权搜索）
                val rows = UsersTable.selectAll()
                    .where {
                        // 全文搜索匹配（username=A, displayName=B, bio=C）
                        CustomOperator<Boolean>(
                            "@@",
                            UsersTable.searchVector(),
                            toTsQuery(tsquery)
                        )
                    }
                    .orderBy(tsRank(UsersTable.searchVector(), toTsQuery(tsquery)) to SortOrder.DESC)
                    .limit(limit + 1).offset(offset.toLong())
                    .toList()

                // 映射为 UserProfile（包含统计信息）
                rows.map { row ->
                    val user = row.toDomain()
                    val stats = exposedUserRepository.calculateUserStats(user.id)
                    UserProfile(user, stats)
                }
            }

            profiles.forEach { profile ->
                emit(Either.Right(profile))
            }

            logger.info("搜索用户成功: query='$trimmedQuery', resultCount=${profiles.size}")

        } catch (e: Exception) {
            logger.error("搜索用户失败: query='$query', error=${e.message}", e)
            emit(Either.Left(SearchError.DatabaseError(e.message ?: "Unknown error")))
        }
    }

    // ========== Helper Functions ==========

    /**
     * 批量查询多个 Post 的媒体附件（解决 N+1 问题）
     * @return Map<PostId, List<MediaAttachment>>
     */
    private fun batchLoadMedia(postIds: List<PostId>): Map<PostId, List<MediaAttachment>> {
        if (postIds.isEmpty()) return emptyMap()

        val postIdValues = postIds.map { it.value }
        val mediaList = MediaTable.selectAll()
            .where { MediaTable.postId inList postIdValues }
            .orderBy(MediaTable.order to SortOrder.ASC)
            .map { row ->
                val postId = PostId(row[MediaTable.postId])
                postId to row.toMediaAttachment()
            }

        return mediaList.groupBy({ it.first }, { it.second })
    }

    /**
     * 将 ResultRow 映射为 PostDetail（包含媒体）
     */
    private fun ResultRow.toPostDetailWithMedia(mediaMap: Map<PostId, List<MediaAttachment>>): PostDetail {
        val postId = PostId(this[PostsTable.id])
        val media = mediaMap[postId] ?: emptyList()

        val post = this.toPost().copy(media = media)
        val author = this.toDomain()
        val stats = this.toPostStats()

        return PostDetail(post, author, stats, parentPost = null)
    }

    // ========== PostgreSQL FTS Custom Functions (Exposed v1 API) ==========

    /**
     * 引用 search_vector 列（tsvector 类型）
     */
    private fun Table.searchVector(): Column<String> {
        return varchar("search_vector", 0) // 长度参数被忽略（tsvector 是原生类型）
    }

    /**
     * to_tsquery('english', query) - 转换查询为 tsquery
     */
    private fun toTsQuery(query: String): Expression<String> {
        return CustomStringFunction(
            functionName = "to_tsquery",
            expr1 = stringLiteral("english"),
            expr2 = stringLiteral(query)
        )
    }

    /**
     * ts_rank(search_vector, tsquery) - 计算相关性分数
     */
    private fun tsRank(searchVector: Column<String>, tsquery: Expression<String>): Expression<Double> {
        return CustomDoubleFunction(
            functionName = "ts_rank",
            expr1 = searchVector,
            expr2 = tsquery
        )
    }

    /**
     * 自定义二元运算符（用于 @@ 运算符）
     */
    private class CustomOperator<T>(
        private val operator: String,
        private val left: Expression<*>,
        private val right: Expression<*>
    ) : Op<T>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.apply {
                append(left)
                append(" $operator ")
                append(right)
            }
        }
    }

    /**
     * 自定义字符串函数（双参数）
     */
    private class CustomStringFunction(
        private val functionName: String,
        private val expr1: Expression<*>,
        private val expr2: Expression<*>
    ) : Expression<String>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.apply {
                append(functionName)
                append("(")
                append(expr1)
                append(", ")
                append(expr2)
                append(")")
            }
        }
    }

    /**
     * 自定义 Double 函数（双参数）
     */
    private class CustomDoubleFunction(
        private val functionName: String,
        private val expr1: Expression<*>,
        private val expr2: Expression<*>
    ) : Expression<Double>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.apply {
                append(functionName)
                append("(")
                append(expr1)
                append(", ")
                append(expr2)
                append(")")
            }
        }
    }
}
