# 搜索功能实现指南（修正版）

## 本指南解决的问题

本文档基于代码审查结果，修正了原设计文档中的以下关键问题：

1. ✅ **FTS 列类型与建库方式统一**：使用原生 SQL + createIndices() 模式
2. ✅ **可选认证正确实现**：使用 authenticateOptional + UserPrincipal + claim "id"
3. ✅ **批量查询避免 N+1**：先收集 ID，一次批量查询，再回填状态
4. ✅ **综合搜索采用 fail-fast**：与 GetTimelineWithStatusUseCase 保持一致
5. ✅ **hasMore 使用 limit + 1**：与项目现有分页策略统一
6. ✅ **错误模型统一**：搜索无结果返回 200 + empty list
7. ✅ **Exposed v1 API**：使用 org.jetbrains.exposed.v1.*
8. ✅ **用户统计信息**：复用 ExposedUserRepository.calculateUserStats()

---

## 实现步骤

### Phase 1: Domain Layer

#### 1.1 SearchQuery (Value Object)

**文件：`src/main/kotlin/domain/model/SearchQuery.kt`**

```kotlin
package com.connor.domain.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.SearchError

@JvmInline
value class SearchQuery private constructor(val value: String) {
    companion object {
        private const val MIN_LENGTH = 2
        private const val MAX_LENGTH = 100

        operator fun invoke(value: String): Either<SearchError, SearchQuery> {
            val normalized = value.trim()
            return when {
                normalized.isBlank() -> SearchError.EmptyQuery.left()
                normalized.length < MIN_LENGTH ->
                    SearchError.QueryTooShort(normalized.length, MIN_LENGTH).left()
                normalized.length > MAX_LENGTH ->
                    SearchError.QueryTooLong(normalized.length, MAX_LENGTH).left()
                else -> SearchQuery(normalized).right()
            }
        }

        fun unsafe(value: String): SearchQuery = SearchQuery(value)
    }
}
```

#### 1.2 SearchSortOrder (Enum)

**文件：`src/main/kotlin/domain/model/SearchSortOrder.kt`**

```kotlin
package com.connor.domain.model

enum class SearchSortOrder {
    /**
     * 最佳匹配 - 使用 ts_rank 相关性分数排序
     */
    BestMatch,

    /**
     * 最新发布 - 按 createdAt 时间倒序
     */
    Latest
}
```

#### 1.3 SearchResult Models

**文件：`src/main/kotlin/domain/model/SearchResult.kt`**

```kotlin
package com.connor.domain.model

/**
 * Post 搜索结果
 */
data class PostSearchResult(
    val postDetail: PostDetail,
    val relevanceScore: Float // 0.0 ~ 1.0，由 ts_rank 计算
)

/**
 * 用户搜索结果
 */
data class UserSearchResult(
    val userProfile: UserProfile,
    val relevanceScore: Float,
    val matchedField: UserMatchField // 匹配的字段
)

/**
 * 用户匹配字段
 */
enum class UserMatchField {
    Username,
    DisplayName,
    Bio
}
```

#### 1.4 SearchError

**文件：`src/main/kotlin/domain/failure/SearchError.kt`**

```kotlin
package com.connor.domain.failure

sealed interface SearchError {
    // 验证错误 (400 Bad Request)
    data object EmptyQuery : SearchError
    data class QueryTooShort(val actual: Int, val min: Int) : SearchError
    data class QueryTooLong(val actual: Int, val max: Int) : SearchError

    // 基础设施错误 (500 Internal Server Error)
    data class SearchServiceUnavailable(val reason: String) : SearchError
}
```

**注意**：移除了 `NoResults` 错误，搜索无结果返回 200 + empty list。

#### 1.5 SearchRepository Interface

**文件：`src/main/kotlin/domain/repository/SearchRepository.kt`**

```kotlin
package com.connor.domain.repository

import com.connor.domain.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Search Repository - Domain 层的端口（Port）
 */
interface SearchRepository {
    /**
     * 搜索 Posts（不包括回复）
     *
     * @param query 搜索查询
     * @param sortOrder 排序方式
     * @param limit 分页大小（实际查询 limit + 1 用于判断 hasMore）
     * @param offset 偏移量
     * @return Flow<PostSearchResult>
     */
    fun searchPosts(
        query: SearchQuery,
        sortOrder: SearchSortOrder = SearchSortOrder.BestMatch,
        limit: Int = 20,
        offset: Int = 0
    ): Flow<PostSearchResult>

    /**
     * 搜索回复（只包括回复，不包括顶层 Posts）
     */
    fun searchReplies(
        query: SearchQuery,
        sortOrder: SearchSortOrder = SearchSortOrder.BestMatch,
        limit: Int = 20,
        offset: Int = 0
    ): Flow<PostSearchResult>

    /**
     * 搜索用户
     *
     * 搜索范围（按权重）：
     * - username (权重 A)
     * - displayName (权重 B)
     * - bio (权重 C)
     */
    fun searchUsers(
        query: SearchQuery,
        limit: Int = 20,
        offset: Int = 0
    ): Flow<UserSearchResult>
}
```

**注意**：移除了 `countXxxResults` 方法，使用 limit + 1 拉取法判断 hasMore。

#### 1.6 SearchPostsUseCase

**文件：`src/main/kotlin/domain/usecase/SearchPostsUseCase.kt`**

```kotlin
package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.BookmarkError
import com.connor.domain.failure.LikeError
import com.connor.domain.failure.SearchError
import com.connor.domain.model.*
import com.connor.domain.repository.PostRepository
import com.connor.domain.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

/**
 * 搜索 Posts 及当前用户的交互状态
 *
 * 设计原理（与 GetTimelineWithStatusUseCase 一致）：
 * - 先收集所有搜索结果
 * - 批量查询交互状态（避免 N+1）
 * - fail-fast：交互状态查询失败时整体返回错误
 */
class SearchPostsUseCase(
    private val searchRepository: SearchRepository,
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(SearchPostsUseCase::class.java)

    /**
     * 搜索结果项（包含交互状态）
     */
    data class SearchResultItem(
        val searchResult: PostSearchResult,
        val isLikedByCurrentUser: Boolean? = null,
        val isBookmarkedByCurrentUser: Boolean? = null
    )

    /**
     * 搜索错误类型
     */
    sealed interface SearchResultError {
        data class QueryValidationFailed(val error: SearchError) : SearchResultError
        data class LikesCheckFailed(val error: LikeError) : SearchResultError
        data class BookmarksCheckFailed(val error: BookmarkError) : SearchResultError
    }

    operator fun invoke(
        query: String,
        sortOrder: SearchSortOrder = SearchSortOrder.BestMatch,
        currentUserId: UserId? = null,
        limit: Int = 20,
        offset: Int = 0
    ): Flow<Either<SearchResultError, SearchResultItem>> = flow {
        logger.info("搜索 Posts: query=$query, sort=$sortOrder, limit=$limit, offset=$offset, userId=${currentUserId?.value}")

        // 1. 验证查询
        val validatedQuery = SearchQuery(query).fold(
            ifLeft = { error ->
                emit(Either.Left(SearchResultError.QueryValidationFailed(error)))
                return@flow
            },
            ifRight = { it }
        )

        // 2. 收集搜索结果（使用 limit + 1 判断 hasMore）
        val results = mutableListOf<PostSearchResult>()
        searchRepository.searchPosts(validatedQuery, sortOrder, limit + 1, offset).collect { result ->
            results.add(result)
        }

        if (results.isEmpty()) {
            return@flow
        }

        // 3. 如果用户已认证，批量查询交互状态
        var likedPostIds: Set<PostId> = emptySet()
        var bookmarkedPostIds: Set<PostId> = emptySet()

        if (currentUserId != null) {
            val postIds = results.map { it.postDetail.post.id }

            // 批量查询点赞状态
            val likedResult = postRepository.batchCheckLiked(currentUserId, postIds)
            if (likedResult.isLeft()) {
                val error = (likedResult as Either.Left).value
                logger.warn("查询点赞状态失败: postCount=${postIds.size}, error=$error")
                emit(Either.Left(SearchResultError.LikesCheckFailed(error)))
                return@flow  // fail-fast
            }
            likedPostIds = (likedResult as Either.Right).value

            // 批量查询收藏状态
            val bookmarkedResult = postRepository.batchCheckBookmarked(currentUserId, postIds)
            if (bookmarkedResult.isLeft()) {
                val error = (bookmarkedResult as Either.Left).value
                logger.warn("查询收藏状态失败: postCount=${postIds.size}, error=$error")
                emit(Either.Left(SearchResultError.BookmarksCheckFailed(error)))
                return@flow  // fail-fast
            }
            bookmarkedPostIds = (bookmarkedResult as Either.Right).value
        }

        // 4. 返回结果（交互状态已准备好，O(1) 查找）
        results.forEach { result ->
            emit(
                Either.Right(
                    SearchResultItem(
                        searchResult = result,
                        isLikedByCurrentUser = if (currentUserId != null) result.postDetail.post.id in likedPostIds else null,
                        isBookmarkedByCurrentUser = if (currentUserId != null) result.postDetail.post.id in bookmarkedPostIds else null
                    )
                )
            )
        }
    }
}
```

**关键改进**：
- ✅ 先收集所有结果到 List
- ✅ 提取所有 postId 后一次批量查询
- ✅ fail-fast 错误处理
- ✅ 使用 limit + 1 拉取（hasMore 判断在 Route 层）

#### 1.7 SearchRepliesUseCase

**文件：`src/main/kotlin/domain/usecase/SearchRepliesUseCase.kt`**

```kotlin
package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.BookmarkError
import com.connor.domain.failure.LikeError
import com.connor.domain.failure.SearchError
import com.connor.domain.model.*
import com.connor.domain.repository.PostRepository
import com.connor.domain.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

/**
 * 搜索 Replies 及当前用户的交互状态
 * 实现逻辑与 SearchPostsUseCase 一致
 */
class SearchRepliesUseCase(
    private val searchRepository: SearchRepository,
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(SearchRepliesUseCase::class.java)

    data class SearchResultItem(
        val searchResult: PostSearchResult,
        val isLikedByCurrentUser: Boolean? = null,
        val isBookmarkedByCurrentUser: Boolean? = null
    )

    sealed interface SearchResultError {
        data class QueryValidationFailed(val error: SearchError) : SearchResultError
        data class LikesCheckFailed(val error: LikeError) : SearchResultError
        data class BookmarksCheckFailed(val error: BookmarkError) : SearchResultError
    }

    operator fun invoke(
        query: String,
        sortOrder: SearchSortOrder = SearchSortOrder.BestMatch,
        currentUserId: UserId? = null,
        limit: Int = 20,
        offset: Int = 0
    ): Flow<Either<SearchResultError, SearchResultItem>> = flow {
        logger.info("搜索 Replies: query=$query, sort=$sortOrder, limit=$limit, offset=$offset, userId=${currentUserId?.value}")

        // 验证查询
        val validatedQuery = SearchQuery(query).fold(
            ifLeft = { error ->
                emit(Either.Left(SearchResultError.QueryValidationFailed(error)))
                return@flow
            },
            ifRight = { it }
        )

        // 收集搜索结果（limit + 1）
        val results = mutableListOf<PostSearchResult>()
        searchRepository.searchReplies(validatedQuery, sortOrder, limit + 1, offset).collect { result ->
            results.add(result)
        }

        if (results.isEmpty()) {
            return@flow
        }

        // 批量查询交互状态
        var likedPostIds: Set<PostId> = emptySet()
        var bookmarkedPostIds: Set<PostId> = emptySet()

        if (currentUserId != null) {
            val postIds = results.map { it.postDetail.post.id }

            val likedResult = postRepository.batchCheckLiked(currentUserId, postIds)
            if (likedResult.isLeft()) {
                val error = (likedResult as Either.Left).value
                logger.warn("查询点赞状态失败: postCount=${postIds.size}, error=$error")
                emit(Either.Left(SearchResultError.LikesCheckFailed(error)))
                return@flow
            }
            likedPostIds = (likedResult as Either.Right).value

            val bookmarkedResult = postRepository.batchCheckBookmarked(currentUserId, postIds)
            if (bookmarkedResult.isLeft()) {
                val error = (bookmarkedResult as Either.Left).value
                logger.warn("查询收藏状态失败: postCount=${postIds.size}, error=$error")
                emit(Either.Left(SearchResultError.BookmarksCheckFailed(error)))
                return@flow
            }
            bookmarkedPostIds = (bookmarkedResult as Either.Right).value
        }

        // 返回结果
        results.forEach { result ->
            emit(
                Either.Right(
                    SearchResultItem(
                        searchResult = result,
                        isLikedByCurrentUser = if (currentUserId != null) result.postDetail.post.id in likedPostIds else null,
                        isBookmarkedByCurrentUser = if (currentUserId != null) result.postDetail.post.id in bookmarkedPostIds else null
                    )
                )
            )
        }
    }
}
```

#### 1.8 SearchUsersUseCase

**文件：`src/main/kotlin/domain/usecase/SearchUsersUseCase.kt`**

```kotlin
package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.SearchError
import com.connor.domain.model.*
import com.connor.domain.repository.SearchRepository
import com.connor.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

/**
 * 搜索用户及当前用户的关注状态
 */
class SearchUsersUseCase(
    private val searchRepository: SearchRepository,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(SearchUsersUseCase::class.java)

    data class SearchResultItem(
        val searchResult: UserSearchResult,
        val isFollowedByCurrentUser: Boolean? = null
    )

    sealed interface SearchResultError {
        data class QueryValidationFailed(val error: SearchError) : SearchResultError
    }

    operator fun invoke(
        query: String,
        currentUserId: UserId? = null,
        limit: Int = 20,
        offset: Int = 0
    ): Flow<Either<SearchResultError, SearchResultItem>> = flow {
        logger.info("搜索用户: query=$query, limit=$limit, offset=$offset, userId=${currentUserId?.value}")

        // 验证查询
        val validatedQuery = SearchQuery(query).fold(
            ifLeft = { error ->
                emit(Either.Left(SearchResultError.QueryValidationFailed(error)))
                return@flow
            },
            ifRight = { it }
        )

        // 收集搜索结果（limit + 1）
        val results = mutableListOf<UserSearchResult>()
        searchRepository.searchUsers(validatedQuery, limit + 1, offset).collect { result ->
            results.add(result)
        }

        if (results.isEmpty()) {
            return@flow
        }

        // 批量查询关注状态
        var followingIds: Set<UserId> = emptySet()

        if (currentUserId != null) {
            val userIds = results.map { it.userProfile.user.id }
            followingIds = userRepository.batchCheckFollowing(currentUserId, userIds)
        }

        // 返回结果
        results.forEach { result ->
            emit(
                Either.Right(
                    SearchResultItem(
                        searchResult = result,
                        isFollowedByCurrentUser = if (currentUserId != null) result.userProfile.user.id in followingIds else null
                    )
                )
            )
        }
    }
}
```

**注意**：`batchCheckFollowing` 不返回 Either（根据现有 UserRepository 接口），所以不需要 fail-fast 处理。

---

### Phase 2: Infrastructure Layer

#### 2.1 添加 search_vector 字段到数据库

**文件：`src/main/kotlin/data/db/DatabaseFactory.kt`（修改）**

在 `init()` 函数中添加搜索向量初始化：

```kotlin
// 在 createIndices() 之后添加
createSearchVectors()
```

然后添加新函数：

```kotlin
/**
 * 创建全文搜索向量（幂等操作）
 *
 * 使用原生 SQL 添加 tsvector 列、触发器和 GIN 索引
 */
private fun JdbcTransaction.createSearchVectors() {
    try {
        logger.info("开始初始化全文搜索向量...")

        // ========== Posts 表 ==========

        // 1. 添加 search_vector 列（如果不存在）
        try {
            exec("""
                ALTER TABLE posts
                ADD COLUMN IF NOT EXISTS search_vector tsvector
            """.trimIndent())
            logger.debug("Posts.search_vector 列创建成功或已存在")
        } catch (e: Exception) {
            if (e.message?.contains("already exists", ignoreCase = true) == true) {
                logger.debug("Posts.search_vector 列已存在（跳过）")
            } else {
                throw e
            }
        }

        // 2. 创建或替换触发器函数
        exec("""
            CREATE OR REPLACE FUNCTION posts_search_vector_update() RETURNS trigger AS $$
            BEGIN
                NEW.search_vector := to_tsvector('english', COALESCE(NEW.content, ''));
                RETURN NEW;
            END
            $$ LANGUAGE plpgsql;
        """.trimIndent())
        logger.debug("Posts 搜索向量触发器函数创建成功")

        // 3. 创建触发器（先删除旧触发器）
        try {
            exec("DROP TRIGGER IF EXISTS posts_search_vector_trigger ON posts")
            exec("""
                CREATE TRIGGER posts_search_vector_trigger
                BEFORE INSERT OR UPDATE ON posts
                FOR EACH ROW
                EXECUTE FUNCTION posts_search_vector_update();
            """.trimIndent())
            logger.debug("Posts 搜索向量触发器创建成功")
        } catch (e: Exception) {
            logger.warn("Posts 触发器创建警告: ${e.message}")
        }

        // 4. 初始化已有数据的 search_vector
        val postsCount = exec("UPDATE posts SET search_vector = to_tsvector('english', COALESCE(content, '')) WHERE search_vector IS NULL")
        logger.info("Posts 搜索向量初始化完成: 更新了 $postsCount 条记录")

        // 5. 创建 GIN 索引
        exec("CREATE INDEX IF NOT EXISTS posts_search_idx ON posts USING GIN(search_vector)")
        logger.debug("Posts 搜索索引创建成功或已存在")

        // ========== Users 表 ==========

        // 1. 添加 search_vector 列
        try {
            exec("""
                ALTER TABLE users
                ADD COLUMN IF NOT EXISTS search_vector tsvector
            """.trimIndent())
            logger.debug("Users.search_vector 列创建成功或已存在")
        } catch (e: Exception) {
            if (e.message?.contains("already exists", ignoreCase = true) == true) {
                logger.debug("Users.search_vector 列已存在（跳过）")
            } else {
                throw e
            }
        }

        // 2. 创建或替换触发器函数（带权重）
        exec("""
            CREATE OR REPLACE FUNCTION users_search_vector_update() RETURNS trigger AS $$
            BEGIN
                NEW.search_vector :=
                    setweight(to_tsvector('english', COALESCE(NEW.username, '')), 'A') ||
                    setweight(to_tsvector('english', COALESCE(NEW.display_name, '')), 'B') ||
                    setweight(to_tsvector('english', COALESCE(NEW.bio, '')), 'C');
                RETURN NEW;
            END
            $$ LANGUAGE plpgsql;
        """.trimIndent())
        logger.debug("Users 搜索向量触发器函数创建成功")

        // 3. 创建触发器
        try {
            exec("DROP TRIGGER IF EXISTS users_search_vector_trigger ON users")
            exec("""
                CREATE TRIGGER users_search_vector_trigger
                BEFORE INSERT OR UPDATE ON users
                FOR EACH ROW
                EXECUTE FUNCTION users_search_vector_update();
            """.trimIndent())
            logger.debug("Users 搜索向量触发器创建成功")
        } catch (e: Exception) {
            logger.warn("Users 触发器创建警告: ${e.message}")
        }

        // 4. 初始化已有数据的 search_vector（带权重）
        val usersCount = exec("""
            UPDATE users
            SET search_vector =
                setweight(to_tsvector('english', COALESCE(username, '')), 'A') ||
                setweight(to_tsvector('english', COALESCE(display_name, '')), 'B') ||
                setweight(to_tsvector('english', COALESCE(bio, '')), 'C')
            WHERE search_vector IS NULL
        """.trimIndent())
        logger.info("Users 搜索向量初始化完成: 更新了 $usersCount 条记录")

        // 5. 创建 GIN 索引
        exec("CREATE INDEX IF NOT EXISTS users_search_idx ON users USING GIN(search_vector)")
        logger.debug("Users 搜索索引创建成功或已存在")

        logger.info("全文搜索向量初始化完成")

    } catch (e: Exception) {
        logger.error("搜索向量初始化失败: ${e.message}", e)
        // 不抛出异常，让应用继续启动（搜索功能不可用但不阻塞主功能）
    }
}
```

**关键改进**：
- ✅ 使用原生 SQL 创建 tsvector 列（不依赖 Exposed 类型映射）
- ✅ 幂等操作：`IF NOT EXISTS` + 异常处理
- ✅ 遵循项目现有的 `createIndices()` 模式

#### 2.2 PostgreSQL Functions (Exposed CustomFunction)

**文件：`src/main/kotlin/data/db/functions/PostgresFunctions.kt`**

```kotlin
package com.connor.data.db.functions

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.jdbc.FloatColumnType
import org.jetbrains.exposed.v1.jdbc.TextColumnType
import org.jetbrains.exposed.v1.jdbc.stringLiteral

/**
 * PostgreSQL plainto_tsquery 函数
 */
class PlainToTsQueryFunction(
    private val config: String,
    private val query: Expression<String>
) : ExpressionWithColumnType<String>() {
    override val columnType = TextColumnType()

    override fun toQueryBuilder(queryBuilder: org.jetbrains.exposed.v1.jdbc.QueryBuilder) {
        queryBuilder {
            append("plainto_tsquery(")
            append(stringLiteral(config))
            append(", ")
            append(query)
            append(")")
        }
    }
}

fun plainToTsQuery(config: String, query: String): PlainToTsQueryFunction =
    PlainToTsQueryFunction(config, stringLiteral(query))

/**
 * PostgreSQL ts_rank 函数
 */
class TsRankFunction(
    private val vector: Expression<String>,
    private val query: Expression<String>
) : ExpressionWithColumnType<Float>() {
    override val columnType = FloatColumnType()

    override fun toQueryBuilder(queryBuilder: org.jetbrains.exposed.v1.jdbc.QueryBuilder) {
        queryBuilder {
            append("ts_rank(")
            append(vector)
            append(", ")
            append(query)
            append(")")
        }
    }
}

fun tsRank(vector: Expression<String>, query: Expression<String>): TsRankFunction =
    TsRankFunction(vector, query)

/**
 * PostgreSQL @@ 运算符（匹配运算符）
 */
infix fun Expression<String>.tsMatch(query: Expression<String>): Op<Boolean> =
    object : Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: org.jetbrains.exposed.v1.jdbc.QueryBuilder) {
            queryBuilder {
                append(this@tsMatch)
                append(" @@ ")
                append(query)
            }
        }
    }

/**
 * 表达式包装器（用于在 WHERE 中使用字符串列）
 */
fun stringColumn(columnName: String): Expression<String> =
    object : ExpressionWithColumnType<String>() {
        override val columnType = TextColumnType()

        override fun toQueryBuilder(queryBuilder: org.jetbrains.exposed.v1.jdbc.QueryBuilder) {
            queryBuilder.append(columnName)
        }
    }
```

**关键改进**：
- ✅ 使用 Exposed v1 API (`org.jetbrains.exposed.v1.*`)
- ✅ 正确实现 `ExpressionWithColumnType` 和 `toQueryBuilder`

#### 2.3 ExposedSearchRepository

**文件：`src/main/kotlin/data/repository/ExposedSearchRepository.kt`**

```kotlin
package com.connor.data.repository

import com.connor.data.db.DatabaseFactory.dbQuery
import com.connor.data.db.functions.*
import com.connor.data.db.mapping.toPost
import com.connor.data.db.mapping.toPostStats
import com.connor.data.db.mapping.toUser
import com.connor.data.db.schema.PostsTable
import com.connor.data.db.schema.UsersTable
import com.connor.domain.model.*
import com.connor.domain.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.isNotNull
import org.jetbrains.exposed.v1.jdbc.isNull
import org.slf4j.LoggerFactory

class ExposedSearchRepository(
    private val userRepository: com.connor.data.repository.ExposedUserRepository // 复用统计信息
) : SearchRepository {

    companion object {
        private const val SEARCH_CONFIG = "english"
    }

    private val logger = LoggerFactory.getLogger(ExposedSearchRepository::class.java)

    override fun searchPosts(
        query: SearchQuery,
        sortOrder: SearchSortOrder,
        limit: Int,
        offset: Int
    ): Flow<PostSearchResult> = flow {
        dbQuery {
            logger.debug("搜索 Posts: query=${query.value}, sort=$sortOrder, limit=$limit, offset=$offset")

            val tsQuery = plainToTsQuery(SEARCH_CONFIG, query.value)
            val rankColumn = tsRank(stringColumn("posts.search_vector"), tsQuery)

            // 使用原生 SQL 列名访问 search_vector
            val searchVectorExpr = stringColumn("posts.search_vector")

            val results = (PostsTable innerJoin UsersTable)
                .selectAll()
                .where { searchVectorExpr tsMatch tsQuery }
                .andWhere { PostsTable.parentId.isNull() } // 只搜索顶层 Posts
                .orderBy(
                    when (sortOrder) {
                        SearchSortOrder.BestMatch -> rankColumn to org.jetbrains.exposed.v1.core.SortOrder.DESC
                        SearchSortOrder.Latest -> PostsTable.createdAt to org.jetbrains.exposed.v1.core.SortOrder.DESC
                    }
                )
                .limit(limit, offset.toLong())
                .map { row ->
                    PostSearchResult(
                        postDetail = PostDetail(
                            post = row.toPost(),
                            author = row.toUser(),
                            stats = row.toPostStats(),
                            parentPost = null
                        ),
                        relevanceScore = 0.0f // 暂不提取 rank，可通过子查询优化
                    )
                }

            results.forEach { emit(it) }
            logger.debug("搜索 Posts 完成: 返回 ${results.size} 条结果")
        }
    }

    override fun searchReplies(
        query: SearchQuery,
        sortOrder: SearchSortOrder,
        limit: Int,
        offset: Int
    ): Flow<PostSearchResult> = flow {
        dbQuery {
            logger.debug("搜索 Replies: query=${query.value}, sort=$sortOrder, limit=$limit, offset=$offset")

            val tsQuery = plainToTsQuery(SEARCH_CONFIG, query.value)
            val rankColumn = tsRank(stringColumn("posts.search_vector"), tsQuery)
            val searchVectorExpr = stringColumn("posts.search_vector")

            val results = (PostsTable innerJoin UsersTable)
                .selectAll()
                .where { searchVectorExpr tsMatch tsQuery }
                .andWhere { PostsTable.parentId.isNotNull() } // 只搜索回复
                .orderBy(
                    when (sortOrder) {
                        SearchSortOrder.BestMatch -> rankColumn to org.jetbrains.exposed.v1.core.SortOrder.DESC
                        SearchSortOrder.Latest -> PostsTable.createdAt to org.jetbrains.exposed.v1.core.SortOrder.DESC
                    }
                )
                .limit(limit, offset.toLong())
                .map { row ->
                    PostSearchResult(
                        postDetail = PostDetail(
                            post = row.toPost(),
                            author = row.toUser(),
                            stats = row.toPostStats(),
                            parentPost = null
                        ),
                        relevanceScore = 0.0f
                    )
                }

            results.forEach { emit(it) }
            logger.debug("搜索 Replies 完成: 返回 ${results.size} 条结果")
        }
    }

    override fun searchUsers(
        query: SearchQuery,
        limit: Int,
        offset: Int
    ): Flow<UserSearchResult> = flow {
        dbQuery {
            logger.debug("搜索用户: query=${query.value}, limit=$limit, offset=$offset")

            val tsQuery = plainToTsQuery(SEARCH_CONFIG, query.value)
            val rankColumn = tsRank(stringColumn("users.search_vector"), tsQuery)
            val searchVectorExpr = stringColumn("users.search_vector")

            val results = UsersTable
                .selectAll()
                .where { searchVectorExpr tsMatch tsQuery }
                .orderBy(rankColumn to org.jetbrains.exposed.v1.core.SortOrder.DESC)
                .limit(limit, offset.toLong())
                .map { row ->
                    val user = row.toUser()
                    UserSearchResult(
                        userProfile = UserProfile(
                            user = user,
                            stats = userRepository.calculateUserStats(user.id) // 复用现有实现
                        ),
                        relevanceScore = 0.0f,
                        matchedField = determineMatchedField(user, query.value)
                    )
                }

            results.forEach { emit(it) }
            logger.debug("搜索用户完成: 返回 ${results.size} 条结果")
        }
    }

    /**
     * 判断用户的哪个字段匹配了查询
     */
    private fun determineMatchedField(user: User, query: String): UserMatchField {
        val lowerQuery = query.lowercase()
        return when {
            user.username.value.lowercase().contains(lowerQuery) -> UserMatchField.Username
            user.displayName.value.lowercase().contains(lowerQuery) -> UserMatchField.DisplayName
            user.bio.value.lowercase().contains(lowerQuery) -> UserMatchField.Bio
            else -> UserMatchField.Username
        }
    }
}
```

**关键改进**：
- ✅ 使用 Exposed v1 API
- ✅ 使用 `stringColumn()` 包装原生列名访问 search_vector
- ✅ 复用 `ExposedUserRepository.calculateUserStats()` 获取用户统计
- ✅ relevanceScore 暂设为 0.0f（可通过子查询优化提取 ts_rank 值）

**注意**：需要在 `ExposedUserRepository` 中将 `calculateUserStats` 改为 `internal` 可见性，或者创建公共方法。

---

### Phase 3: Transport Layer

#### 3.1 Search DTOs

**文件：`src/main/kotlin/features/search/SearchSchema.kt`**

```kotlin
package com.connor.features.search

import com.connor.features.post.PostDetailResponse
import com.connor.features.user.UserProfileResponse
import kotlinx.serialization.Serializable

// ========== 响应 DTOs ==========

/**
 * Post 搜索结果
 */
@Serializable
data class PostSearchResultDto(
    val post: PostDetailResponse,
    val relevanceScore: Float,
    val isLikedByCurrentUser: Boolean? = null,
    val isBookmarkedByCurrentUser: Boolean? = null
)

/**
 * 用户搜索结果
 */
@Serializable
data class UserSearchResultDto(
    val user: UserProfileResponse,
    val relevanceScore: Float,
    val matchedField: String, // "username" | "displayName" | "bio"
    val isFollowedByCurrentUser: Boolean? = null
)

/**
 * 搜索结果列表（带分页）
 */
@Serializable
data class PostSearchListResponse(
    val results: List<PostSearchResultDto>,
    val hasMore: Boolean
)

@Serializable
data class UserSearchListResponse(
    val results: List<UserSearchResultDto>,
    val hasMore: Boolean
)

/**
 * 错误响应
 */
@Serializable
data class SearchErrorResponse(
    val error: String,
    val message: String
)
```

#### 3.2 Search Mappers

**文件：`src/main/kotlin/features/search/SearchMappers.kt`**

```kotlin
package com.connor.features.search

import com.connor.domain.failure.SearchError
import com.connor.domain.usecase.*
import com.connor.features.post.toResponse
import com.connor.features.user.toResponse
import io.ktor.http.HttpStatusCode

// ========== Domain -> DTO ==========

fun SearchPostsUseCase.SearchResultItem.toDto(): PostSearchResultDto =
    PostSearchResultDto(
        post = searchResult.postDetail.toResponse(),
        relevanceScore = searchResult.relevanceScore,
        isLikedByCurrentUser = isLikedByCurrentUser,
        isBookmarkedByCurrentUser = isBookmarkedByCurrentUser
    )

fun SearchRepliesUseCase.SearchResultItem.toDto(): PostSearchResultDto =
    PostSearchResultDto(
        post = searchResult.postDetail.toResponse(),
        relevanceScore = searchResult.relevanceScore,
        isLikedByCurrentUser = isLikedByCurrentUser,
        isBookmarkedByCurrentUser = isBookmarkedByCurrentUser
    )

fun SearchUsersUseCase.SearchResultItem.toDto(): UserSearchResultDto =
    UserSearchResultDto(
        user = searchResult.userProfile.toResponse(),
        relevanceScore = searchResult.relevanceScore,
        matchedField = searchResult.matchedField.name.lowercase(),
        isFollowedByCurrentUser = isFollowedByCurrentUser
    )

// ========== Error -> HTTP ==========

fun SearchError.toHttpError(): Pair<HttpStatusCode, SearchErrorResponse> {
    return when (this) {
        is SearchError.EmptyQuery -> HttpStatusCode.BadRequest to SearchErrorResponse(
            error = "empty_query",
            message = "搜索查询不能为空"
        )
        is SearchError.QueryTooShort -> HttpStatusCode.BadRequest to SearchErrorResponse(
            error = "query_too_short",
            message = "搜索查询至少需要 $min 个字符，当前为 $actual 个"
        )
        is SearchError.QueryTooLong -> HttpStatusCode.BadRequest to SearchErrorResponse(
            error = "query_too_long",
            message = "搜索查询不能超过 $max 个字符，当前为 $actual 个"
        )
        is SearchError.SearchServiceUnavailable -> HttpStatusCode.InternalServerError to SearchErrorResponse(
            error = "search_unavailable",
            message = "搜索服务暂时不可用：$reason"
        )
    }
}

fun SearchPostsUseCase.SearchResultError.toHttpError(): Pair<HttpStatusCode, SearchErrorResponse> {
    return when (this) {
        is SearchPostsUseCase.SearchResultError.QueryValidationFailed -> error.toHttpError()
        is SearchPostsUseCase.SearchResultError.LikesCheckFailed -> HttpStatusCode.InternalServerError to SearchErrorResponse(
            error = "likes_check_failed",
            message = "查询点赞状态失败"
        )
        is SearchPostsUseCase.SearchResultError.BookmarksCheckFailed -> HttpStatusCode.InternalServerError to SearchErrorResponse(
            error = "bookmarks_check_failed",
            message = "查询收藏状态失败"
        )
    }
}

fun SearchRepliesUseCase.SearchResultError.toHttpError(): Pair<HttpStatusCode, SearchErrorResponse> {
    return when (this) {
        is SearchRepliesUseCase.SearchResultError.QueryValidationFailed -> error.toHttpError()
        is SearchRepliesUseCase.SearchResultError.LikesCheckFailed -> HttpStatusCode.InternalServerError to SearchErrorResponse(
            error = "likes_check_failed",
            message = "查询点赞状态失败"
        )
        is SearchRepliesUseCase.SearchResultError.BookmarksCheckFailed -> HttpStatusCode.InternalServerError to SearchErrorResponse(
            error = "bookmarks_check_failed",
            message = "查询收藏状态失败"
        )
    }
}

fun SearchUsersUseCase.SearchResultError.toHttpError(): Pair<HttpStatusCode, SearchErrorResponse> {
    return when (this) {
        is SearchUsersUseCase.SearchResultError.QueryValidationFailed -> error.toHttpError()
    }
}
```

#### 3.3 Search Routes

**文件：`src/main/kotlin/features/search/SearchRoutes.kt`**

```kotlin
package com.connor.features.search

import arrow.core.Either
import com.connor.core.security.UserPrincipal
import com.connor.domain.model.SearchSortOrder
import com.connor.domain.model.UserId
import com.connor.domain.usecase.*
import com.connor.plugins.authenticateOptional
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SearchRoutes")

fun Route.searchRoutes(
    searchPostsUseCase: SearchPostsUseCase,
    searchRepliesUseCase: SearchRepliesUseCase,
    searchUsersUseCase: SearchUsersUseCase
) {
    route("/v1/search") {

        // 所有搜索路由使用可选认证
        authenticateOptional("auth-jwt") {

            /**
             * GET /v1/search/posts
             *
             * 搜索 Posts（不包括回复）
             *
             * Query Parameters:
             * - q: 搜索关键词（必填）
             * - sort: 排序方式（可选，默认 best_match）
             *   - best_match: 按相关性排序
             *   - latest: 按时间倒序
             * - limit: 分页大小（可选，默认 20，最大 100）
             * - offset: 偏移量（可选，默认 0）
             */
            get("/posts") {
                val startTime = System.currentTimeMillis()

                val query = call.request.queryParameters["q"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        SearchErrorResponse("missing_query", "缺少查询参数 q")
                    )

                val sortParam = call.request.queryParameters["sort"] ?: "best_match"
                val sortOrder = when (sortParam) {
                    "latest" -> SearchSortOrder.Latest
                    else -> SearchSortOrder.BestMatch
                }

                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val limit = rawLimit.coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

                // 获取当前用户（可选认证）
                val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                logger.info("搜索 Posts: query=$query, sort=$sortOrder, limit=$limit, offset=$offset, userId=${currentUserId?.value}")

                try {
                    // 调用 UseCase
                    val searchItems = searchPostsUseCase(query, sortOrder, currentUserId, limit, offset).toList()
                    val duration = System.currentTimeMillis() - startTime

                    // fail-fast：检查是否有错误
                    val failures = searchItems.filterIsInstance<Either.Left<*>>()
                    if (failures.isNotEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        val error = (failures.first() as Either.Left<SearchPostsUseCase.SearchResultError>).value
                        logger.warn("搜索 Posts 失败: duration=${duration}ms, error=$error")
                        val (status, errorResponse) = error.toHttpError()
                        call.respond(status, errorResponse)
                        return@get
                    }

                    // 提取成功的结果
                    @Suppress("UNCHECKED_CAST")
                    val successItems = searchItems.filterIsInstance<Either.Right<*>>()
                        .map { (it as Either.Right<SearchPostsUseCase.SearchResultItem>).value }

                    logger.info("搜索 Posts 成功: count=${successItems.size}, duration=${duration}ms")

                    // 计算 hasMore 并裁剪结果（limit + 1 模式）
                    val hasMore = successItems.size > limit
                    val itemsToReturn = if (hasMore) successItems.take(limit) else successItems

                    // 映射为响应 DTO
                    val response = PostSearchListResponse(
                        results = itemsToReturn.map { it.toDto() },
                        hasMore = hasMore
                    )

                    call.respond(response)
                } catch (e: Exception) {
                    logger.error("搜索 Posts 异常: ${e.message}", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        SearchErrorResponse("search_error", "搜索服务异常")
                    )
                }
            }

            /**
             * GET /v1/search/replies
             *
             * 搜索回复（只包括回复，不包括顶层 Posts）
             */
            get("/replies") {
                val startTime = System.currentTimeMillis()

                val query = call.request.queryParameters["q"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        SearchErrorResponse("missing_query", "缺少查询参数 q")
                    )

                val sortParam = call.request.queryParameters["sort"] ?: "best_match"
                val sortOrder = when (sortParam) {
                    "latest" -> SearchSortOrder.Latest
                    else -> SearchSortOrder.BestMatch
                }

                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val limit = rawLimit.coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

                val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                logger.info("搜索 Replies: query=$query, sort=$sortOrder, limit=$limit, offset=$offset, userId=${currentUserId?.value}")

                try {
                    val searchItems = searchRepliesUseCase(query, sortOrder, currentUserId, limit, offset).toList()
                    val duration = System.currentTimeMillis() - startTime

                    val failures = searchItems.filterIsInstance<Either.Left<*>>()
                    if (failures.isNotEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        val error = (failures.first() as Either.Left<SearchRepliesUseCase.SearchResultError>).value
                        logger.warn("搜索 Replies 失败: duration=${duration}ms, error=$error")
                        val (status, errorResponse) = error.toHttpError()
                        call.respond(status, errorResponse)
                        return@get
                    }

                    @Suppress("UNCHECKED_CAST")
                    val successItems = searchItems.filterIsInstance<Either.Right<*>>()
                        .map { (it as Either.Right<SearchRepliesUseCase.SearchResultItem>).value }

                    logger.info("搜索 Replies 成功: count=${successItems.size}, duration=${duration}ms")

                    val hasMore = successItems.size > limit
                    val itemsToReturn = if (hasMore) successItems.take(limit) else successItems

                    val response = PostSearchListResponse(
                        results = itemsToReturn.map { it.toDto() },
                        hasMore = hasMore
                    )

                    call.respond(response)
                } catch (e: Exception) {
                    logger.error("搜索 Replies 异常: ${e.message}", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        SearchErrorResponse("search_error", "搜索服务异常")
                    )
                }
            }

            /**
             * GET /v1/search/users
             *
             * 搜索用户
             */
            get("/users") {
                val startTime = System.currentTimeMillis()

                val query = call.request.queryParameters["q"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        SearchErrorResponse("missing_query", "缺少查询参数 q")
                    )

                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val limit = rawLimit.coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

                val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                logger.info("搜索用户: query=$query, limit=$limit, offset=$offset, userId=${currentUserId?.value}")

                try {
                    val searchItems = searchUsersUseCase(query, currentUserId, limit, offset).toList()
                    val duration = System.currentTimeMillis() - startTime

                    val failures = searchItems.filterIsInstance<Either.Left<*>>()
                    if (failures.isNotEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        val error = (failures.first() as Either.Left<SearchUsersUseCase.SearchResultError>).value
                        logger.warn("搜索用户失败: duration=${duration}ms, error=$error")
                        val (status, errorResponse) = error.toHttpError()
                        call.respond(status, errorResponse)
                        return@get
                    }

                    @Suppress("UNCHECKED_CAST")
                    val successItems = searchItems.filterIsInstance<Either.Right<*>>()
                        .map { (it as Either.Right<SearchUsersUseCase.SearchResultItem>).value }

                    logger.info("搜索用户成功: count=${successItems.size}, duration=${duration}ms")

                    val hasMore = successItems.size > limit
                    val itemsToReturn = if (hasMore) successItems.take(limit) else successItems

                    val response = UserSearchListResponse(
                        results = itemsToReturn.map { it.toDto() },
                        hasMore = hasMore
                    )

                    call.respond(response)
                } catch (e: Exception) {
                    logger.error("搜索用户异常: ${e.message}", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        SearchErrorResponse("search_error", "搜索服务异常")
                    )
                }
            }
        }
    }
}
```

**关键改进**：
- ✅ 使用 `authenticateOptional("auth-jwt")` 包裹所有路由
- ✅ 通过 `call.principal<UserPrincipal>()?.userId` 获取当前用户（claim "id" 已在 UserPrincipal 中映射为 userId）
- ✅ fail-fast 错误处理（与 PostRoutes 一致）
- ✅ limit + 1 拉取法 + hasMore 计算
- ✅ 详细日志记录

---

### Phase 4: DI 配置

#### 4.1 DataModule

**文件：`src/main/kotlin/core/di/DataModule.kt`（修改）**

```kotlin
// 添加到现有 DataModule
single<SearchRepository> {
    ExposedSearchRepository(get<ExposedUserRepository>()) // 注入 UserRepository 以复用统计信息
}
```

#### 4.2 DomainModule

**文件：`src/main/kotlin/core/di/DomainModule.kt`（修改）**

```kotlin
// 添加到现有 DomainModule

// Search Use Cases
single { SearchPostsUseCase(get(), get()) }
single { SearchRepliesUseCase(get(), get()) }
single { SearchUsersUseCase(get(), get()) }
```

#### 4.3 Routing

**文件：`src/main/kotlin/plugins/Routing.kt`（修改）**

```kotlin
import com.connor.features.search.searchRoutes
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    // ... 现有代码

    routing {
        // ... 现有路由

        // 搜索路由
        val searchPostsUseCase: SearchPostsUseCase by inject()
        val searchRepliesUseCase: SearchRepliesUseCase by inject()
        val searchUsersUseCase: SearchUsersUseCase by inject()

        searchRoutes(
            searchPostsUseCase,
            searchRepliesUseCase,
            searchUsersUseCase
        )
    }
}
```

---

### Phase 5: ExposedUserRepository 修改

**文件：`src/main/kotlin/data/repository/ExposedUserRepository.kt`（修改）**

将 `calculateUserStats` 方法改为 `internal` 可见性，以便 `ExposedSearchRepository` 可以调用：

```kotlin
/**
 * 计算用户统计信息
 * （改为 internal 以便 SearchRepository 复用）
 */
internal suspend fun calculateUserStats(userId: UserId): UserStats = dbQuery {
    // ... 现有实现
}
```

---

## 测试建议

### 单元测试

```kotlin
class SearchQueryTest {
    @Test
    fun `should reject empty query`() {
        val result = SearchQuery("")
        assertTrue(result.isLeft())
    }

    @Test
    fun `should reject query too short`() {
        val result = SearchQuery("a")
        assertTrue(result.isLeft())
    }

    @Test
    fun `should accept valid query`() {
        val result = SearchQuery("kotlin")
        assertTrue(result.isRight())
    }
}
```

### API 测试

```bash
# 测试搜索 Posts
curl "http://localhost:8080/v1/search/posts?q=kotlin&sort=best_match&limit=20"

# 测试搜索用户
curl "http://localhost:8080/v1/search/users?q=connor&limit=20"

# 测试认证用户搜索
curl -H "Authorization: Bearer <token>" \
     "http://localhost:8080/v1/search/posts?q=kotlin"
```

---

## 实现清单

### Domain Layer
- [ ] `SearchQuery.kt`
- [ ] `SearchSortOrder.kt`
- [ ] `SearchResult.kt`
- [ ] `SearchError.kt`
- [ ] `SearchRepository.kt`
- [ ] `SearchPostsUseCase.kt`
- [ ] `SearchRepliesUseCase.kt`
- [ ] `SearchUsersUseCase.kt`

### Infrastructure Layer
- [ ] `DatabaseFactory.kt` (添加 createSearchVectors())
- [ ] `PostgresFunctions.kt`
- [ ] `ExposedSearchRepository.kt`
- [ ] `ExposedUserRepository.kt` (修改 calculateUserStats 可见性)

### Transport Layer
- [ ] `SearchSchema.kt`
- [ ] `SearchMappers.kt`
- [ ] `SearchRoutes.kt`

### DI Configuration
- [ ] `DataModule.kt` (注册 SearchRepository)
- [ ] `DomainModule.kt` (注册 Use Cases)
- [ ] `Routing.kt` (注册 searchRoutes)

---

## 已修正的问题总结

1. ✅ **FTS 列类型**：使用原生 SQL 创建 tsvector，不依赖 Exposed 类型映射
2. ✅ **可选认证**：使用 `authenticateOptional` + `UserPrincipal` + claim "id"
3. ✅ **批量查询**：先收集所有 ID，一次批量查询，避免 N+1
4. ✅ **综合搜索**：移除 SearchAllUseCase，客户端可分别调用三个接口
5. ✅ **hasMore 计算**：使用 limit + 1 拉取法
6. ✅ **错误模型**：搜索无结果返回 200 + empty list
7. ✅ **Exposed API**：统一使用 v1 API
8. ✅ **用户统计**：复用 ExposedUserRepository.calculateUserStats()

---

**修正完成！** 🎉

现在可以开始实现了。
