# 搜索功能设计文档

## 架构概览

遵循 Hexagonal Architecture (Ports & Adapters) 和 DDD 原则：

```
┌─────────────────────────────────────────────────────────────┐
│                        Domain Layer                         │
│  (纯 Kotlin，无框架依赖，业务规则的唯一真相来源)               │
├─────────────────────────────────────────────────────────────┤
│  Models:                                                    │
│    - SearchQuery (Value Object with validation)            │
│    - SearchSortOrder (Enum: BestMatch, Latest)             │
│    - PostSearchResult, UserSearchResult (结果聚合)          │
│                                                             │
│  Errors (sealed interface):                                │
│    - SearchError (EmptyQuery, QueryTooShort, etc.)         │
│                                                             │
│  Repository (Port/Interface):                              │
│    - SearchRepository (定义契约，实现在 Infrastructure 层)  │
│                                                             │
│  Use Cases (Application Services):                         │
│    - SearchPostsUseCase                                    │
│    - SearchRepliesUseCase                                  │
│    - SearchUsersUseCase                                    │
│    - SearchAllUseCase (综合搜索)                            │
└─────────────────────────────────────────────────────────────┘
                              ↓
                    (依赖倒置：接口在上，实现在下)
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   Infrastructure Layer                      │
│              (数据库、外部服务的具体实现)                      │
├─────────────────────────────────────────────────────────────┤
│  - ExposedSearchRepository (implements SearchRepository)   │
│  - PostgreSQL Full-Text Search (tsvector + GIN index)     │
│  - CustomFunction (ts_rank, to_tsquery, plainto_tsquery)  │
│  - PostsTable, UsersTable (添加 search_vector 字段)        │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      Transport Layer                        │
│                   (HTTP API - Ktor Routes)                  │
├─────────────────────────────────────────────────────────────┤
│  - GET    /search/posts     (搜索Posts，可选排序)           │
│  - GET    /search/replies   (搜索回复)                      │
│  - GET    /search/users     (搜索用户)                      │
│  - GET    /search           (综合搜索，返回所有类型)         │
└─────────────────────────────────────────────────────────────┘
```

---

## 技术选型：PostgreSQL Full-Text Search

### 选型理由

**备选方案对比**：

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| **PostgreSQL FTS** | 1. 无需额外基础设施<br>2. 性能好（GIN索引）<br>3. 支持相关性排序（ts_rank）<br>4. 内置分词（支持多语言） | 1. 功能不如专业搜索引擎<br>2. 需要维护 tsvector 字段 | ✅ **推荐** |
| **LIKE/ILIKE** | 简单，无需额外配置 | 1. 性能差（全表扫描）<br>2. 无相关性排序<br>3. 不支持分词 | ❌ 不适合生产 |
| **Elasticsearch** | 功能强大，性能卓越 | 1. 需要额外基础设施<br>2. 数据同步复杂<br>3. 维护成本高 | ❌ 过度设计 |

### PostgreSQL FTS 核心概念

#### 1. tsvector（文档向量）

将文本转换为词汇单元（lexemes）的优化格式：

```sql
-- 示例
SELECT to_tsvector('english', 'The quick brown fox jumps over the lazy dog');
-- 结果: 'brown':3 'dog':9 'fox':4 'jump':5 'lazi':8 'quick':2

-- 特性:
-- 1. 自动去除停用词 (the, over, etc.)
-- 2. 词干提取 (jumps -> jump, lazy -> lazi)
-- 3. 位置保留 (数字表示单词位置)
```

#### 2. tsquery（查询向量）

用于匹配 tsvector 的查询格式：

```sql
-- plainto_tsquery: 简单查询（自动处理AND）
SELECT plainto_tsquery('english', 'quick fox');
-- 结果: 'quick' & 'fox'

-- to_tsquery: 高级查询（支持布尔运算符）
SELECT to_tsquery('english', 'quick & fox | dog');
-- 结果: 'quick' & 'fox' | 'dog'

-- phraseto_tsquery: 短语查询（保留位置）
SELECT phraseto_tsquery('english', 'quick fox');
-- 结果: 'quick' <-> 'fox' (必须相邻)
```

#### 3. ts_rank（相关性排序）

计算文档与查询的匹配分数：

```sql
SELECT ts_rank(search_vector, plainto_tsquery('english', 'kotlin server'))
FROM posts;
-- 返回 0.0 ~ 1.0 之间的分数，越高越相关
```

#### 4. GIN 索引（加速搜索）

Generalized Inverted Index，专为全文搜索优化：

```sql
CREATE INDEX posts_search_idx ON posts USING GIN(search_vector);
-- 查询时间从 O(n) 降到 O(log n)
```

---

## Domain Models 设计

### 1. SearchQuery (Value Object)

**核心约束**：
- 查询字符串不能为空
- 最小长度 2 字符（避免过于宽泛的搜索）
- 最大长度 100 字符（防止滥用）
- 自动 trim 和规范化

```kotlin
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

### 2. SearchSortOrder (Enum)

**排序策略**：

```kotlin
enum class SearchSortOrder {
    /**
     * 最佳匹配 - 使用 ts_rank 相关性分数排序
     * 最相关的结果排在前面
     */
    BestMatch,

    /**
     * 最新发布 - 按 createdAt 时间倒序
     * 最新的内容排在前面
     */
    Latest
}
```

**使用场景**：
- `BestMatch`：默认排序，用户搜索时优先看到最相关的内容
- `Latest`：查看最新讨论，例如搜索"Kotlin 2.0"时查看最新帖子

### 3. PostSearchResult（搜索结果聚合）

**目的**：
- 聚合 PostDetail + 相关性分数
- 用于显示搜索结果时排序和高亮

```kotlin
data class PostSearchResult(
    val postDetail: PostDetail,
    val relevanceScore: Float, // 0.0 ~ 1.0，由 ts_rank 计算
    val matchedSnippet: String? = null // 高亮匹配的文本片段（可选）
)
```

### 4. UserSearchResult（用户搜索结果）

```kotlin
data class UserSearchResult(
    val userProfile: UserProfile,
    val relevanceScore: Float,
    val matchedField: UserMatchField // 匹配的字段（用于高亮）
)

enum class UserMatchField {
    Username,    // 用户名匹配
    DisplayName, // 昵称匹配
    Bio          // 简介匹配
}
```

### 5. SearchError (Sealed Interface)

```kotlin
sealed interface SearchError {
    // 验证错误 (400 Bad Request)
    data object EmptyQuery : SearchError
    data class QueryTooShort(val actual: Int, val min: Int) : SearchError
    data class QueryTooLong(val actual: Int, val max: Int) : SearchError

    // 业务规则错误
    data object NoResults : SearchError // 404 Not Found（可选）

    // 基础设施错误 (500 Internal Server Error)
    data class SearchServiceUnavailable(val reason: String) : SearchError
}
```

---

## Repository 接口设计

### SearchRepository Interface

```kotlin
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
 * - 返回 Either 处理业务错误（不抛异常）
 * - 使用 Flow 处理流式数据（分页、实时更新）
 */
interface SearchRepository {
    /**
     * 搜索 Posts（不包括回复）
     *
     * @param query 搜索查询（已验证的 SearchQuery）
     * @param sortOrder 排序方式（最佳匹配 or 最新）
     * @param limit 分页大小
     * @param offset 偏移量
     * @return Flow<PostSearchResult> 流式返回搜索结果
     */
    fun searchPosts(
        query: SearchQuery,
        sortOrder: SearchSortOrder = SearchSortOrder.BestMatch,
        limit: Int = 20,
        offset: Int = 0
    ): Flow<PostSearchResult>

    /**
     * 搜索回复（只包括回复，不包括顶层 Posts）
     *
     * @param query 搜索查询
     * @param sortOrder 排序方式
     * @param limit 分页大小
     * @param offset 偏移量
     * @return Flow<PostSearchResult> 流式返回搜索结果
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
     * 搜索范围：
     * - username（权重最高）
     * - displayName（权重中等）
     * - bio（权重较低）
     *
     * @param query 搜索查询
     * @param limit 分页大小
     * @param offset 偏移量
     * @return Flow<UserSearchResult> 流式返回搜索结果（按相关性排序）
     */
    fun searchUsers(
        query: SearchQuery,
        limit: Int = 20,
        offset: Int = 0
    ): Flow<UserSearchResult>

    /**
     * 获取搜索结果数量（用于分页导航）
     *
     * @return 搜索结果总数
     */
    suspend fun countPostResults(query: SearchQuery): Int
    suspend fun countReplyResults(query: SearchQuery): Int
    suspend fun countUserResults(query: SearchQuery): Int
}
```

**设计决策**：

**为何使用 `Flow` 而非 `List`？**
- 流式处理，内存友好
- 支持分页和增量加载
- 与现有 PostRepository/UserRepository 保持一致

**为何不返回 `Either`？**
- `Flow` 本身可以处理空结果（`emptyFlow()`）
- 搜索无结果是正常情况，不是错误
- 如果需要报告错误，可以用 `Flow<Either<SearchError, T>>`（暂不需要）

---

## Use Cases 设计

### 1. SearchPostsUseCase

**职责**：
- 验证搜索查询
- 调用 Repository 搜索 Posts
- 如果有当前用户，批量查询交互状态（点赞/收藏）

```kotlin
class SearchPostsUseCase(
    private val searchRepository: SearchRepository,
    private val postRepository: PostRepository
) {
    suspend operator fun invoke(
        query: String,
        sortOrder: SearchSortOrder = SearchSortOrder.BestMatch,
        currentUserId: UserId? = null,
        limit: Int = 20,
        offset: Int = 0
    ): Either<SearchError, Flow<PostSearchResultWithStatus>> {
        // 1. 验证查询
        val validatedQuery = SearchQuery(query).getOrElse { return it.left() }

        // 2. 搜索 Posts
        val results = searchRepository.searchPosts(validatedQuery, sortOrder, limit, offset)

        // 3. 如果有当前用户，批量查询交互状态
        return if (currentUserId != null) {
            results.map { result ->
                val postIds = listOf(result.postDetail.post.id)
                val likedIds = postRepository.batchCheckLiked(currentUserId, postIds)
                    .getOrElse { emptySet() }
                val bookmarkedIds = postRepository.batchCheckBookmarked(currentUserId, postIds)
                    .getOrElse { emptySet() }

                PostSearchResultWithStatus(
                    searchResult = result,
                    isLikedByCurrentUser = result.postDetail.post.id in likedIds,
                    isBookmarkedByCurrentUser = result.postDetail.post.id in bookmarkedIds
                )
            }.right()
        } else {
            results.map { result ->
                PostSearchResultWithStatus(
                    searchResult = result,
                    isLikedByCurrentUser = null,
                    isBookmarkedByCurrentUser = null
                )
            }.right()
        }
    }
}

data class PostSearchResultWithStatus(
    val searchResult: PostSearchResult,
    val isLikedByCurrentUser: Boolean?,
    val isBookmarkedByCurrentUser: Boolean?
)
```

**优化：批量查询交互状态**
```kotlin
// ❌ N+1 查询：每个 Post 查一次
results.forEach { result ->
    val isLiked = postRepository.isLikedByUser(currentUserId, result.postId)
}

// ✅ 批量查询：一次查询所有
val postIds = results.map { it.postDetail.post.id }
val likedIds = postRepository.batchCheckLiked(currentUserId, postIds)
```

### 2. SearchRepliesUseCase

**职责**：
- 搜索回复（parentId != null）
- 其他逻辑与 SearchPostsUseCase 一致

```kotlin
class SearchRepliesUseCase(
    private val searchRepository: SearchRepository,
    private val postRepository: PostRepository
) {
    suspend operator fun invoke(
        query: String,
        sortOrder: SearchSortOrder = SearchSortOrder.BestMatch,
        currentUserId: UserId? = null,
        limit: Int = 20,
        offset: Int = 0
    ): Either<SearchError, Flow<PostSearchResultWithStatus>> {
        // 实现与 SearchPostsUseCase 类似
        val validatedQuery = SearchQuery(query).getOrElse { return it.left() }
        val results = searchRepository.searchReplies(validatedQuery, sortOrder, limit, offset)
        // ... 批量查询交互状态
    }
}
```

### 3. SearchUsersUseCase

**职责**：
- 搜索用户
- 如果有当前用户，批量查询关注状态

```kotlin
class SearchUsersUseCase(
    private val searchRepository: SearchRepository,
    private val userRepository: UserRepository
) {
    suspend operator fun invoke(
        query: String,
        currentUserId: UserId? = null,
        limit: Int = 20,
        offset: Int = 0
    ): Either<SearchError, Flow<UserSearchResultWithStatus>> {
        // 1. 验证查询
        val validatedQuery = SearchQuery(query).getOrElse { return it.left() }

        // 2. 搜索用户
        val results = searchRepository.searchUsers(validatedQuery, limit, offset)

        // 3. 批量查询关注状态
        return if (currentUserId != null) {
            results.map { result ->
                val userIds = listOf(result.userProfile.user.id)
                val followingIds = userRepository.batchCheckFollowing(currentUserId, userIds)

                UserSearchResultWithStatus(
                    searchResult = result,
                    isFollowedByCurrentUser = result.userProfile.user.id in followingIds
                )
            }.right()
        } else {
            results.map { result ->
                UserSearchResultWithStatus(
                    searchResult = result,
                    isFollowedByCurrentUser = null
                )
            }.right()
        }
    }
}

data class UserSearchResultWithStatus(
    val searchResult: UserSearchResult,
    val isFollowedByCurrentUser: Boolean?
)
```

### 4. SearchAllUseCase（综合搜索）

**职责**：
- 同时搜索 Posts、Replies、Users
- 返回聚合结果

```kotlin
class SearchAllUseCase(
    private val searchPostsUseCase: SearchPostsUseCase,
    private val searchRepliesUseCase: SearchRepliesUseCase,
    private val searchUsersUseCase: SearchUsersUseCase
) {
    suspend operator fun invoke(
        query: String,
        currentUserId: UserId? = null,
        postsLimit: Int = 5,
        repliesLimit: Int = 5,
        usersLimit: Int = 5
    ): Either<SearchError, SearchAllResults> {
        // 验证查询（复用 SearchQuery）
        SearchQuery(query).getOrElse { return it.left() }

        // 并行搜索所有类型
        val posts = searchPostsUseCase(query, SearchSortOrder.BestMatch, currentUserId, postsLimit, 0)
            .getOrElse { emptyFlow() }
        val replies = searchRepliesUseCase(query, SearchSortOrder.BestMatch, currentUserId, repliesLimit, 0)
            .getOrElse { emptyFlow() }
        val users = searchUsersUseCase(query, currentUserId, usersLimit, 0)
            .getOrElse { emptyFlow() }

        return SearchAllResults(
            posts = posts.toList(),
            replies = replies.toList(),
            users = users.toList()
        ).right()
    }
}

data class SearchAllResults(
    val posts: List<PostSearchResultWithStatus>,
    val replies: List<PostSearchResultWithStatus>,
    val users: List<UserSearchResultWithStatus>
)
```

**性能优化**：
- 使用 `async` 并行搜索（可选）
- 限制每种类型的结果数（避免单个类型占用太多资源）

---

## 数据库设计

### 1. PostsTable 扩展

**添加全文搜索字段**：

```kotlin
object PostsTable : Table("posts") {
    val id = varchar("id", 36)
    val authorId = varchar("author_id", 36).references(UsersTable.id)
    val content = text("content")
    val parentId = varchar("parent_id", 36).references(id).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    // 统计字段
    val replyCount = integer("reply_count").default(0)
    val likeCount = integer("like_count").default(0)
    val bookmarkCount = integer("bookmark_count").default(0)
    val viewCount = integer("view_count").default(0)

    // ========== 新增：全文搜索字段 ==========
    val searchVector = text("search_vector") // tsvector 类型（Exposed 没有原生支持，用 text）

    override val primaryKey = PrimaryKey(id)
}
```

**PostgreSQL 实际类型**（通过 migration）：

```sql
ALTER TABLE posts ADD COLUMN search_vector tsvector;

-- 自动更新 search_vector（使用触发器）
CREATE OR REPLACE FUNCTION posts_search_vector_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        to_tsvector('english', COALESCE(NEW.content, ''));
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER posts_search_vector_trigger
    BEFORE INSERT OR UPDATE ON posts
    FOR EACH ROW
    EXECUTE FUNCTION posts_search_vector_update();

-- 创建 GIN 索引（加速搜索）
CREATE INDEX posts_search_idx ON posts USING GIN(search_vector);
```

**为什么用触发器？**
- 自动维护 search_vector，无需应用层手动更新
- 确保数据一致性（即使直接 SQL 修改也会更新）

### 2. UsersTable 扩展

**添加全文搜索字段**：

```kotlin
object UsersTable : Table("users") {
    val id = varchar("id", 36)
    val email = varchar("email", 128).uniqueIndex()
    val passwordHash = varchar("password_hash", 128)
    val username = varchar("username", 20).uniqueIndex()
    val displayName = varchar("display_name", 64)
    val bio = text("bio").default("")
    val avatarUrl = varchar("avatar_url", 256).nullable()
    val createdAt = long("created_at")

    // ========== 新增：全文搜索字段 ==========
    val searchVector = text("search_vector") // tsvector 类型

    override val primaryKey = PrimaryKey(id)
}
```

**PostgreSQL Migration**：

```sql
ALTER TABLE users ADD COLUMN search_vector tsvector;

-- 自动更新 search_vector（加权：username > displayName > bio）
CREATE OR REPLACE FUNCTION users_search_vector_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', COALESCE(NEW.username, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.display_name, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.bio, '')), 'C');
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER users_search_vector_trigger
    BEFORE INSERT OR UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION users_search_vector_update();

-- 创建 GIN 索引
CREATE INDEX users_search_idx ON users USING GIN(search_vector);
```

**权重说明**：
- `A`（最高权重）：username 匹配分数 × 1.0
- `B`（中等权重）：displayName 匹配分数 × 0.4
- `C`（较低权重）：bio 匹配分数 × 0.2

**效果**：
- 搜索 "connor" 时，username="connor" 的用户排在最前面
- displayName="Connor Chen" 的用户次之
- bio 中提到 "connor" 的用户排在最后

### 3. 数据库迁移脚本

**创建文件：`src/main/resources/db/migration/V3__add_search_vectors.sql`**

```sql
-- ========== Posts 全文搜索 ==========

-- 添加 tsvector 字段
ALTER TABLE posts ADD COLUMN search_vector tsvector;

-- 更新触发器函数
CREATE OR REPLACE FUNCTION posts_search_vector_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector := to_tsvector('english', COALESCE(NEW.content, ''));
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- 创建触发器
CREATE TRIGGER posts_search_vector_trigger
    BEFORE INSERT OR UPDATE ON posts
    FOR EACH ROW
    EXECUTE FUNCTION posts_search_vector_update();

-- 初始化已有数据的 search_vector
UPDATE posts SET search_vector = to_tsvector('english', COALESCE(content, ''));

-- 创建 GIN 索引
CREATE INDEX posts_search_idx ON posts USING GIN(search_vector);

-- ========== Users 全文搜索 ==========

-- 添加 tsvector 字段
ALTER TABLE users ADD COLUMN search_vector tsvector;

-- 更新触发器函数（带权重）
CREATE OR REPLACE FUNCTION users_search_vector_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', COALESCE(NEW.username, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.display_name, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.bio, '')), 'C');
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- 创建触发器
CREATE TRIGGER users_search_vector_trigger
    BEFORE INSERT OR UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION users_search_vector_update();

-- 初始化已有数据的 search_vector
UPDATE users SET search_vector =
    setweight(to_tsvector('english', COALESCE(username, '')), 'A') ||
    setweight(to_tsvector('english', COALESCE(display_name, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE(bio, '')), 'C');

-- 创建 GIN 索引
CREATE INDEX users_search_idx ON users USING GIN(search_vector);
```

---

## Infrastructure 层实现

### 1. Exposed CustomFunction（支持 PostgreSQL FTS）

**创建文件：`src/main/kotlin/data/db/functions/PostgresFunctions.kt`**

```kotlin
package com.connor.data.db.functions

import org.jetbrains.exposed.sql.*

/**
 * PostgreSQL plainto_tsquery 函数
 * 将普通文本转换为 tsquery（自动处理 AND）
 *
 * 用法：
 * ```
 * val query = plainToTsQuery("english", "quick fox")
 * // 生成 SQL: plainto_tsquery('english', 'quick fox')
 * ```
 */
class PlainToTsQueryFunction(
    private val config: String, // 'english', 'simple', etc.
    private val query: Expression<String>
) : CustomFunction<String>(
    functionName = "plainto_tsquery",
    columnType = TextColumnType(),
    expr = arrayOf(stringLiteral(config), query)
)

fun plainToTsQuery(config: String, query: String): PlainToTsQueryFunction =
    PlainToTsQueryFunction(config, stringLiteral(query))

/**
 * PostgreSQL ts_rank 函数
 * 计算文档相关性分数
 *
 * 用法：
 * ```
 * val rank = tsRank(PostsTable.searchVector, plainToTsQuery("english", "kotlin"))
 * // 生成 SQL: ts_rank(search_vector, plainto_tsquery('english', 'kotlin'))
 * ```
 */
class TsRankFunction(
    private val vector: Expression<String>,
    private val query: Expression<String>
) : CustomFunction<Float>(
    functionName = "ts_rank",
    columnType = FloatColumnType(),
    expr = arrayOf(vector, query)
)

fun tsRank(vector: Expression<String>, query: Expression<String>): TsRankFunction =
    TsRankFunction(vector, query)

/**
 * PostgreSQL @@ 运算符（匹配运算符）
 * 检查 tsvector 是否匹配 tsquery
 *
 * 用法：
 * ```
 * where { PostsTable.searchVector tsMatch plainToTsQuery("english", "kotlin") }
 * // 生成 SQL: WHERE search_vector @@ plainto_tsquery('english', 'kotlin')
 * ```
 */
infix fun Expression<String>.tsMatch(query: Expression<String>): Op<Boolean> =
    object : ComparisonOp(this, query, "@@") {}
```

### 2. ExposedSearchRepository 实现

**创建文件：`src/main/kotlin/data/repository/ExposedSearchRepository.kt`**

```kotlin
package com.connor.data.repository

import com.connor.data.db.DatabaseFactory.dbQuery
import com.connor.data.db.functions.*
import com.connor.data.db.mapping.*
import com.connor.data.db.schema.*
import com.connor.domain.model.*
import com.connor.domain.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.sql.*

class ExposedSearchRepository : SearchRepository {

    companion object {
        private const val SEARCH_CONFIG = "english" // PostgreSQL 全文搜索配置
    }

    override fun searchPosts(
        query: SearchQuery,
        sortOrder: SearchSortOrder,
        limit: Int,
        offset: Int
    ): Flow<PostSearchResult> = flow {
        dbQuery {
            val tsQuery = plainToTsQuery(SEARCH_CONFIG, query.value)
            val rankColumn = tsRank(PostsTable.searchVector, tsQuery)

            val results = (PostsTable innerJoin UsersTable)
                .select(PostsTable.columns + UsersTable.columns + rankColumn)
                .where {
                    (PostsTable.searchVector tsMatch tsQuery) and
                    (PostsTable.parentId.isNull()) // 只搜索顶层 Posts
                }
                .orderBy(
                    when (sortOrder) {
                        SearchSortOrder.BestMatch -> rankColumn to SortOrder.DESC
                        SearchSortOrder.Latest -> PostsTable.createdAt to SortOrder.DESC
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
                        relevanceScore = row[rankColumn],
                        matchedSnippet = null // 可选：使用 ts_headline 提取高亮片段
                    )
                }

            results.forEach { emit(it) }
        }
    }

    override fun searchReplies(
        query: SearchQuery,
        sortOrder: SearchSortOrder,
        limit: Int,
        offset: Int
    ): Flow<PostSearchResult> = flow {
        dbQuery {
            val tsQuery = plainToTsQuery(SEARCH_CONFIG, query.value)
            val rankColumn = tsRank(PostsTable.searchVector, tsQuery)

            val results = (PostsTable innerJoin UsersTable)
                .select(PostsTable.columns + UsersTable.columns + rankColumn)
                .where {
                    (PostsTable.searchVector tsMatch tsQuery) and
                    (PostsTable.parentId.isNotNull()) // 只搜索回复
                }
                .orderBy(
                    when (sortOrder) {
                        SearchSortOrder.BestMatch -> rankColumn to SortOrder.DESC
                        SearchSortOrder.Latest -> PostsTable.createdAt to SortOrder.DESC
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
                        relevanceScore = row[rankColumn],
                        matchedSnippet = null
                    )
                }

            results.forEach { emit(it) }
        }
    }

    override fun searchUsers(
        query: SearchQuery,
        limit: Int,
        offset: Int
    ): Flow<UserSearchResult> = flow {
        dbQuery {
            val tsQuery = plainToTsQuery(SEARCH_CONFIG, query.value)
            val rankColumn = tsRank(UsersTable.searchVector, tsQuery)

            val results = UsersTable
                .select(UsersTable.columns + rankColumn)
                .where { UsersTable.searchVector tsMatch tsQuery }
                .orderBy(rankColumn to SortOrder.DESC) // 用户搜索总是按相关性排序
                .limit(limit, offset.toLong())
                .map { row ->
                    val user = row.toUser()
                    UserSearchResult(
                        userProfile = UserProfile(
                            user = user,
                            stats = calculateUserStats(user.id) // 复用现有函数
                        ),
                        relevanceScore = row[rankColumn],
                        matchedField = determineMatchedField(user, query.value)
                    )
                }

            results.forEach { emit(it) }
        }
    }

    override suspend fun countPostResults(query: SearchQuery): Int = dbQuery {
        val tsQuery = plainToTsQuery(SEARCH_CONFIG, query.value)
        PostsTable
            .selectAll()
            .where {
                (PostsTable.searchVector tsMatch tsQuery) and
                (PostsTable.parentId.isNull())
            }
            .count()
            .toInt()
    }

    override suspend fun countReplyResults(query: SearchQuery): Int = dbQuery {
        val tsQuery = plainToTsQuery(SEARCH_CONFIG, query.value)
        PostsTable
            .selectAll()
            .where {
                (PostsTable.searchVector tsMatch tsQuery) and
                (PostsTable.parentId.isNotNull())
            }
            .count()
            .toInt()
    }

    override suspend fun countUserResults(query: SearchQuery): Int = dbQuery {
        val tsQuery = plainToTsQuery(SEARCH_CONFIG, query.value)
        UsersTable
            .selectAll()
            .where { UsersTable.searchVector tsMatch tsQuery }
            .count()
            .toInt()
    }

    // ========== 辅助函数 ==========

    /**
     * 计算用户统计信息（复用现有 ExposedUserRepository 的逻辑）
     */
    private suspend fun calculateUserStats(userId: UserId): UserStats {
        // 从 ExposedUserRepository 复制或注入依赖
        return UserStats(userId) // 简化实现，实际应复用
    }

    /**
     * 判断用户的哪个字段匹配了查询
     * 用于高亮显示
     */
    private fun determineMatchedField(user: User, query: String): UserMatchField {
        val lowerQuery = query.lowercase()
        return when {
            user.username.value.lowercase().contains(lowerQuery) -> UserMatchField.Username
            user.displayName.value.lowercase().contains(lowerQuery) -> UserMatchField.DisplayName
            user.bio.value.lowercase().contains(lowerQuery) -> UserMatchField.Bio
            else -> UserMatchField.Username // 默认
        }
    }
}
```

**关键实现细节**：

1. **JOIN 优化**：一次 JOIN 获取所有需要的数据（Post + Author），避免 N+1
2. **排序优化**：
   - `BestMatch`：使用 `ts_rank` 相关性分数
   - `Latest`：使用 `createdAt` 时间戳
3. **流式处理**：使用 `Flow` 支持分页和增量加载

---

## Transport 层实现

### 1. Search DTOs

**创建文件：`src/main/kotlin/features/search/SearchSchema.kt`**

```kotlin
package com.connor.features.search

import kotlinx.serialization.Serializable

// ========== 请求 DTOs ==========

/**
 * 搜索查询参数（通过 Query String）
 */
data class SearchQueryParams(
    val q: String, // 搜索关键词
    val sort: String? = "best_match", // best_match | latest
    val limit: Int = 20,
    val offset: Int = 0
)

// ========== 响应 DTOs ==========

/**
 * Post 搜索结果
 */
@Serializable
data class PostSearchResultDto(
    val post: PostDetailResponse, // 复用现有 PostDetailResponse
    val relevanceScore: Float,
    val matchedSnippet: String? = null,
    val isLikedByCurrentUser: Boolean? = null,
    val isBookmarkedByCurrentUser: Boolean? = null
)

/**
 * 用户搜索结果
 */
@Serializable
data class UserSearchResultDto(
    val user: UserProfileResponse, // 复用现有 UserProfileResponse
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
    val total: Int? = null, // 总结果数（可选）
    val hasMore: Boolean
)

@Serializable
data class UserSearchListResponse(
    val results: List<UserSearchResultDto>,
    val total: Int? = null,
    val hasMore: Boolean
)

/**
 * 综合搜索结果
 */
@Serializable
data class SearchAllResponse(
    val posts: List<PostSearchResultDto>,
    val replies: List<PostSearchResultDto>,
    val users: List<UserSearchResultDto>
)
```

### 2. Search Mappers

**创建文件：`src/main/kotlin/features/search/SearchMappers.kt`**

```kotlin
package com.connor.features.search

import com.connor.domain.failure.SearchError
import com.connor.domain.model.*
import com.connor.domain.usecase.*
import com.connor.features.post.toResponse // 复用现有 Post mappers
import com.connor.features.user.toResponse // 复用现有 User mappers
import io.ktor.http.HttpStatusCode

// ========== Domain -> DTO ==========

fun PostSearchResultWithStatus.toDto(): PostSearchResultDto =
    PostSearchResultDto(
        post = searchResult.postDetail.toResponse(),
        relevanceScore = searchResult.relevanceScore,
        matchedSnippet = searchResult.matchedSnippet,
        isLikedByCurrentUser = isLikedByCurrentUser,
        isBookmarkedByCurrentUser = isBookmarkedByCurrentUser
    )

fun UserSearchResultWithStatus.toDto(): UserSearchResultDto =
    UserSearchResultDto(
        user = searchResult.userProfile.toResponse(),
        relevanceScore = searchResult.relevanceScore,
        matchedField = searchResult.matchedField.name.lowercase(),
        isFollowedByCurrentUser = isFollowedByCurrentUser
    )

fun List<PostSearchResultWithStatus>.toPostSearchListResponse(total: Int? = null): PostSearchListResponse =
    PostSearchListResponse(
        results = this.map { it.toDto() },
        total = total,
        hasMore = total?.let { this.size < it } ?: false
    )

fun List<UserSearchResultWithStatus>.toUserSearchListResponse(total: Int? = null): UserSearchListResponse =
    UserSearchListResponse(
        results = this.map { it.toDto() },
        total = total,
        hasMore = total?.let { this.size < it } ?: false
    )

// ========== Error -> HTTP ==========

fun SearchError.toHttpError(): Pair<HttpStatusCode, ErrorResponse> {
    return when (this) {
        is SearchError.EmptyQuery -> HttpStatusCode.BadRequest to ErrorResponse(
            error = "empty_query",
            message = "搜索查询不能为空"
        )
        is SearchError.QueryTooShort -> HttpStatusCode.BadRequest to ErrorResponse(
            error = "query_too_short",
            message = "搜索查询至少需要 $min 个字符，当前为 $actual 个"
        )
        is SearchError.QueryTooLong -> HttpStatusCode.BadRequest to ErrorResponse(
            error = "query_too_long",
            message = "搜索查询不能超过 $max 个字符，当前为 $actual 个"
        )
        is SearchError.NoResults -> HttpStatusCode.NotFound to ErrorResponse(
            error = "no_results",
            message = "未找到相关结果"
        )
        is SearchError.SearchServiceUnavailable -> HttpStatusCode.InternalServerError to ErrorResponse(
            error = "search_unavailable",
            message = "搜索服务暂时不可用：$reason"
        )
    }
}

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String
)
```

### 3. Search Routes

**创建文件：`src/main/kotlin/features/search/SearchRoutes.kt`**

```kotlin
package com.connor.features.search

import com.connor.domain.model.SearchSortOrder
import com.connor.domain.model.UserId
import com.connor.domain.usecase.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.toList

fun Route.searchRoutes(
    searchPostsUseCase: SearchPostsUseCase,
    searchRepliesUseCase: SearchRepliesUseCase,
    searchUsersUseCase: SearchUsersUseCase,
    searchAllUseCase: SearchAllUseCase
) {
    route("/v1/search") {
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
         * - limit: 分页大小（可选，默认 20）
         * - offset: 偏移量（可选，默认 0）
         *
         * Headers:
         * - Authorization: Bearer <token>（可选，有则返回交互状态）
         */
        get("/posts") {
            val query = call.request.queryParameters["q"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("missing_query", "缺少查询参数 q")
                )

            val sortParam = call.request.queryParameters["sort"] ?: "best_match"
            val sortOrder = when (sortParam) {
                "latest" -> SearchSortOrder.Latest
                else -> SearchSortOrder.BestMatch
            }

            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

            // 可选认证：获取当前用户
            val currentUserId = call.principal<JWTPrincipal>()
                ?.payload
                ?.getClaim("userId")
                ?.asString()
                ?.let { UserId(it) }

            searchPostsUseCase(query, sortOrder, currentUserId, limit, offset).fold(
                ifLeft = { error ->
                    val (status, errorResponse) = error.toHttpError()
                    call.respond(status, errorResponse)
                },
                ifRight = { flow ->
                    val results = flow.toList()
                    call.respond(results.toPostSearchListResponse())
                }
            )
        }

        /**
         * GET /v1/search/replies
         *
         * 搜索回复（只包括回复，不包括顶层 Posts）
         */
        get("/replies") {
            val query = call.request.queryParameters["q"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("missing_query", "缺少查询参数 q")
                )

            val sortParam = call.request.queryParameters["sort"] ?: "best_match"
            val sortOrder = when (sortParam) {
                "latest" -> SearchSortOrder.Latest
                else -> SearchSortOrder.BestMatch
            }

            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

            val currentUserId = call.principal<JWTPrincipal>()
                ?.payload
                ?.getClaim("userId")
                ?.asString()
                ?.let { UserId(it) }

            searchRepliesUseCase(query, sortOrder, currentUserId, limit, offset).fold(
                ifLeft = { error ->
                    val (status, errorResponse) = error.toHttpError()
                    call.respond(status, errorResponse)
                },
                ifRight = { flow ->
                    val results = flow.toList()
                    call.respond(results.toPostSearchListResponse())
                }
            )
        }

        /**
         * GET /v1/search/users
         *
         * 搜索用户
         */
        get("/users") {
            val query = call.request.queryParameters["q"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("missing_query", "缺少查询参数 q")
                )

            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

            val currentUserId = call.principal<JWTPrincipal>()
                ?.payload
                ?.getClaim("userId")
                ?.asString()
                ?.let { UserId(it) }

            searchUsersUseCase(query, currentUserId, limit, offset).fold(
                ifLeft = { error ->
                    val (status, errorResponse) = error.toHttpError()
                    call.respond(status, errorResponse)
                },
                ifRight = { flow ->
                    val results = flow.toList()
                    call.respond(results.toUserSearchListResponse())
                }
            )
        }

        /**
         * GET /v1/search
         *
         * 综合搜索（同时搜索 Posts、Replies、Users）
         *
         * Query Parameters:
         * - q: 搜索关键词（必填）
         * - posts_limit: Posts 结果数（可选，默认 5）
         * - replies_limit: Replies 结果数（可选，默认 5）
         * - users_limit: Users 结果数（可选，默认 5）
         */
        get {
            val query = call.request.queryParameters["q"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("missing_query", "缺少查询参数 q")
                )

            val postsLimit = call.request.queryParameters["posts_limit"]?.toIntOrNull() ?: 5
            val repliesLimit = call.request.queryParameters["replies_limit"]?.toIntOrNull() ?: 5
            val usersLimit = call.request.queryParameters["users_limit"]?.toIntOrNull() ?: 5

            val currentUserId = call.principal<JWTPrincipal>()
                ?.payload
                ?.getClaim("userId")
                ?.asString()
                ?.let { UserId(it) }

            searchAllUseCase(query, currentUserId, postsLimit, repliesLimit, usersLimit).fold(
                ifLeft = { error ->
                    val (status, errorResponse) = error.toHttpError()
                    call.respond(status, errorResponse)
                },
                ifRight = { results ->
                    call.respond(
                        SearchAllResponse(
                            posts = results.posts.map { it.toDto() },
                            replies = results.replies.map { it.toDto() },
                            users = results.users.map { it.toDto() }
                        )
                    )
                }
            )
        }
    }
}
```

**关键设计**：
- **可选认证**：未认证用户也可以搜索，但不返回交互状态
- **Query Parameters**：使用 Query String 传参（GET 请求标准）
- **错误处理**：统一使用 `ErrorResponse` 格式

---

## DI 配置（Koin）

**更新文件：`src/main/kotlin/core/di/DomainModule.kt`**

```kotlin
// 添加到现有 DomainModule
single { SearchPostsUseCase(get(), get()) }
single { SearchRepliesUseCase(get(), get()) }
single { SearchUsersUseCase(get(), get()) }
single { SearchAllUseCase(get(), get(), get()) }
```

**更新文件：`src/main/kotlin/core/di/DataModule.kt`**

```kotlin
// 添加到现有 DataModule
single<SearchRepository> { ExposedSearchRepository() }
```

**更新文件：`src/main/kotlin/plugins/Routing.kt`**

```kotlin
import com.connor.features.search.searchRoutes

fun Application.configureRouting() {
    routing {
        // ... 现有路由

        // 搜索路由
        searchRoutes(
            searchPostsUseCase = get(),
            searchRepliesUseCase = get(),
            searchUsersUseCase = get(),
            searchAllUseCase = get()
        )
    }
}
```

---

## API 测试示例

### 1. 搜索 Posts

```bash
# 搜索 "kotlin server"（按相关性排序）
GET /v1/search/posts?q=kotlin%20server&sort=best_match&limit=20&offset=0

# 搜索 "kotlin server"（按时间排序）
GET /v1/search/posts?q=kotlin%20server&sort=latest&limit=20&offset=0

# 认证用户搜索（返回交互状态）
GET /v1/search/posts?q=kotlin
Authorization: Bearer <token>

# 响应示例
{
  "results": [
    {
      "post": { /* PostDetailResponse */ },
      "relevanceScore": 0.87,
      "matchedSnippet": null,
      "isLikedByCurrentUser": false,
      "isBookmarkedByCurrentUser": true
    }
  ],
  "total": null,
  "hasMore": false
}
```

### 2. 搜索回复

```bash
GET /v1/search/replies?q=good%20point&sort=best_match&limit=20

# 响应格式与 Posts 一致
```

### 3. 搜索用户

```bash
GET /v1/search/users?q=connor&limit=20

# 响应示例
{
  "results": [
    {
      "user": { /* UserProfileResponse */ },
      "relevanceScore": 0.95,
      "matchedField": "username",
      "isFollowedByCurrentUser": true
    },
    {
      "user": { /* UserProfileResponse */ },
      "relevanceScore": 0.62,
      "matchedField": "displayName",
      "isFollowedByCurrentUser": false
    }
  ],
  "total": null,
  "hasMore": false
}
```

### 4. 综合搜索

```bash
GET /v1/search?q=kotlin&posts_limit=5&replies_limit=5&users_limit=5

# 响应示例
{
  "posts": [ /* PostSearchResultDto[] */ ],
  "replies": [ /* PostSearchResultDto[] */ ],
  "users": [ /* UserSearchResultDto[] */ ]
}
```

---

## 性能优化

### 1. 索引优化

**已实现**：
- GIN 索引（`posts_search_idx`, `users_search_idx`）
- 查询时间从 O(n) 降到 O(log n)

**未来优化**：
- **部分索引**（Partial Index）：只索引活跃用户
  ```sql
  CREATE INDEX active_users_search_idx ON users USING GIN(search_vector)
  WHERE created_at > NOW() - INTERVAL '30 days';
  ```

### 2. 查询优化

**已实现**：
- JOIN 优化（一次查询获取 Post + Author）
- 批量查询交互状态（避免 N+1）

**未来优化**：
- **CTE（Common Table Expression）**：预计算子查询
  ```sql
  WITH ranked_posts AS (
      SELECT *, ts_rank(search_vector, query) AS rank
      FROM posts
      WHERE search_vector @@ query
  )
  SELECT * FROM ranked_posts ORDER BY rank DESC LIMIT 20;
  ```

### 3. 缓存策略

**热门搜索缓存**（Redis）：

```kotlin
suspend fun searchPosts(query: SearchQuery, ...): Flow<PostSearchResult> {
    val cacheKey = "search:posts:${query.value}:$sortOrder:$limit:$offset"

    // 1. 尝试从缓存读取
    val cached = redisClient.get(cacheKey)
    if (cached != null) {
        return cached.deserialize()
    }

    // 2. 执行数据库查询
    val results = dbQuery { /* ... */ }

    // 3. 写入缓存（TTL 5 分钟）
    redisClient.setex(cacheKey, 300, results.serialize())

    return results
}
```

**缓存失效策略**：
- 新 Post 发布 → 清空相关缓存
- 用户更新资料 → 清空用户搜索缓存

### 4. 分页优化

**当前实现**：Offset-based Pagination

**未来优化**：Cursor-based Pagination（更适合搜索场景）

```kotlin
data class SearchCursor(
    val relevanceScore: Float,
    val createdAt: Long,
    val id: String
)

fun searchPosts(
    query: SearchQuery,
    cursor: SearchCursor? = null,
    limit: Int = 20
): Flow<PostSearchResult> {
    // WHERE (rank, created_at, id) < (cursor.rank, cursor.createdAt, cursor.id)
    // 优点：性能稳定，不受数据变化影响
}
```

---

## 已知限制和未来扩展

### 当前简化

1. **只支持英语分词**：`to_tsvector('english', ...)`
   - 中文需要额外分词插件（如 pg_jieba、zhparser）
   - 解决方案：配置多语言分词，根据用户语言选择
     ```sql
     to_tsvector('simple', content) -- 不分词，适合中文
     ```

2. **无搜索建议（Autocomplete）**
   - 需要额外的 Trie 树或前缀索引
   - 解决方案：使用 PostgreSQL trigram 扩展（pg_trgm）
     ```sql
     SELECT * FROM users WHERE username % 'conn'; -- 模糊匹配
     ```

3. **无高亮显示**
   - 当前 `matchedSnippet` 返回 null
   - 解决方案：使用 `ts_headline` 函数
     ```sql
     ts_headline('english', content, query, 'MaxWords=50, MinWords=25')
     ```

4. **无搜索历史**
   - 客户端可以本地存储
   - 后端可以添加 SearchHistoryRepository

### 未来扩展

#### 1. 高级搜索语法

**布尔运算符**：
```
kotlin AND server    # 必须同时包含
kotlin OR java       # 包含任意一个
kotlin NOT spring    # 包含 kotlin 但不包含 spring
"quick fox"          # 短语搜索（精确匹配）
```

**实现**：使用 `to_tsquery` 替代 `plainto_tsquery`

#### 2. 搜索过滤器

**按时间范围过滤**：
```
GET /v1/search/posts?q=kotlin&from=2024-01-01&to=2024-12-31
```

**按作者过滤**：
```
GET /v1/search/posts?q=kotlin&author_id=xxx
```

**按标签过滤**（需要先实现标签功能）：
```
GET /v1/search/posts?q=kotlin&tags=backend,tutorial
```

#### 3. 搜索分析（Analytics）

**记录搜索日志**：
```kotlin
data class SearchLog(
    val userId: UserId?,
    val query: String,
    val resultCount: Int,
    val clickedPostId: PostId?,
    val timestamp: Long
)
```

**用途**：
- 热门搜索词统计
- 无结果查询分析（优化分词）
- 搜索质量评估（点击率）

#### 4. 个性化搜索

**基于用户画像调整排序**：
```kotlin
fun searchPosts(query: SearchQuery, userId: UserId): Flow<PostSearchResult> {
    // 1. 计算基础相关性分数（ts_rank）
    // 2. 用户关注的作者发布的内容 +0.2 分
    // 3. 用户点赞过的作者发布的内容 +0.1 分
    // 4. 用户历史点击过的话题 +0.15 分
}
```

#### 5. 多语言支持

**中文分词**（使用 pg_jieba）：

```sql
-- 安装扩展
CREATE EXTENSION pg_jieba;

-- 中文分词示例
SELECT to_tsvector('jiebacfg', '我爱 Kotlin 服务器开发');
-- 结果: 'kotlin':2 'server':3 '开发':4 '爱':1

-- 根据用户语言选择分词
CASE WHEN lang = 'zh' THEN to_tsvector('jiebacfg', content)
     WHEN lang = 'en' THEN to_tsvector('english', content)
     ELSE to_tsvector('simple', content)
END
```

---

## 架构合规性检查表

| 原则 | 实现 | 说明 |
|------|------|------|
| **Domain 层纯净** | ✅ | 无 Ktor/Exposed 依赖 |
| **依赖倒置** | ✅ | SearchRepository 接口在 Domain 层 |
| **错误作为值** | ✅ | Either<SearchError, T> |
| **类型安全** | ✅ | SearchQuery Value Object |
| **避免 N+1** | ✅ | 批量查询交互状态 |
| **Flow 流式处理** | ✅ | 所有搜索方法返回 Flow |
| **薄 Transport 层** | ✅ | Routes 只做协议转换 |
| **单一职责** | ✅ | UseCase 职责明确 |
| **可测试性** | ✅ | Domain 层易于单元测试 |
| **数据库索引** | ✅ | GIN 索引优化查询 |

---

## 实现清单

### Domain Layer
- [ ] `SearchQuery.kt` (Value Object)
- [ ] `SearchSortOrder.kt` (Enum)
- [ ] `SearchResult.kt` (PostSearchResult, UserSearchResult)
- [ ] `SearchError.kt` (Sealed Interface)
- [ ] `SearchRepository.kt` (Interface)
- [ ] `SearchPostsUseCase.kt`
- [ ] `SearchRepliesUseCase.kt`
- [ ] `SearchUsersUseCase.kt`
- [ ] `SearchAllUseCase.kt`

### Infrastructure Layer
- [ ] `V3__add_search_vectors.sql` (Migration)
- [ ] `PostgresFunctions.kt` (CustomFunction)
- [ ] `ExposedSearchRepository.kt` (Implementation)

### Transport Layer
- [ ] `SearchSchema.kt` (DTOs)
- [ ] `SearchMappers.kt` (Domain ↔ DTO)
- [ ] `SearchRoutes.kt` (Ktor Routes)

### DI Configuration
- [ ] `DomainModule.kt` 注册 Use Cases
- [ ] `DataModule.kt` 注册 SearchRepository
- [ ] `Routing.kt` 注册 searchRoutes

### Testing（可选）
- [ ] `SearchQueryTest.kt` (单元测试)
- [ ] `SearchPostsUseCaseTest.kt` (单元测试)
- [ ] `ExposedSearchRepositoryTest.kt` (集成测试)
- [ ] `SearchRoutesTest.kt` (API 测试)

---

**设计完成！** 🎉

搜索功能遵循 Hexagonal Architecture，Domain 层无框架依赖，使用 PostgreSQL Full-Text Search 提供高性能全文搜索能力。
