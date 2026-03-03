# 点赞(Like)和收藏(Bookmark)功能设计文档

## 目录
1. [架构概览](#架构概览)
2. [Domain Models 设计](#domain-models-设计)
3. [数据库设计](#数据库设计)
4. [Repository 接口设计](#repository-接口设计)
5. [Use Cases 设计](#use-cases-设计)
6. [API 路由设计](#api-路由设计)
7. [数据流示例](#数据流示例)
8. [Post 与 Like/Bookmark 的关系](#post-与-likebookmark-的关系)
9. [错误处理](#错误处理)
10. [未来扩展](#未来扩展)

---

## 架构概览

遵循 Hexagonal Architecture 和 DDD 原则，在现有 Post 功能基础上扩展 Like 和 Bookmark 功能：

```
┌─────────────────────────────────────────────────────────────┐
│                        Domain Layer                         │
│  (纯 Kotlin，无框架依赖，业务规则的唯一真相来源)               │
├─────────────────────────────────────────────────────────────┤
│  Models:                                                    │
│    - Like (聚合根) - 用户点赞一个Post                        │
│    - Bookmark (聚合根) - 用户收藏一个Post                    │
│    - LikeError, BookmarkError (sealed interface)           │
│                                                             │
│  Repository (Port/Interface):                              │
│    - PostRepository 扩展方法:                               │
│      - likePost(), unlikePost()                            │
│      - bookmarkPost(), unbookmarkPost()                    │
│      - findUserLikes()                                     │
│      - findUserBookmarks()                                 │
│      - isLikedByUser(), isBookmarkedByUser()               │
│                                                             │
│  Use Cases (Application Services):                         │
│    - LikePostUseCase                                       │
│    - UnlikePostUseCase                                     │
│    - BookmarkPostUseCase                                   │
│    - UnbookmarkPostUseCase                                 │
│    - GetUserLikesUseCase                                   │
│    - GetUserBookmarksUseCase                               │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   Infrastructure Layer                      │
│              (数据库、外部服务的具体实现)                      │
├─────────────────────────────────────────────────────────────┤
│  - LikesTable, BookmarksTable (Exposed schema)             │
│  - ExposedPostRepository 扩展实现                          │
│  - Like/Bookmark Mapping (DAO ↔ Domain Entity)            │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      Transport Layer                        │
│                   (HTTP API - Ktor Routes)                  │
├─────────────────────────────────────────────────────────────┤
│  - POST   /v1/posts/{postId}/like      (点赞)              │
│  - DELETE /v1/posts/{postId}/like      (取消点赞)           │
│  - POST   /v1/posts/{postId}/bookmark  (收藏)              │
│  - DELETE /v1/posts/{postId}/bookmark  (取消收藏)           │
│  - GET    /v1/users/{userId}/likes     (用户点赞列表)       │
│  - GET    /v1/users/{userId}/bookmarks (用户收藏列表)       │
└─────────────────────────────────────────────────────────────┘
```

---

## Domain Models 设计

### 1. Like (聚合根)

**核心约束**：
- 每个 Like 属于一个用户 (`userId: UserId`)
- 每个 Like 关联一个 Post (`postId: PostId`)
- (userId, postId) 复合唯一约束：一个用户只能对一个Post点赞一次
- 记录点赞时间 (`createdAt: Long`)

```kotlin
@JvmInline
value class LikeId(val value: String)

data class Like(
    val id: LikeId,
    val userId: UserId,
    val postId: PostId,
    val createdAt: Long
)
```

**业务规则**：
- Like 不能重复（通过复合唯一约束保证）
- Like 创建时必须验证 Post 存在
- Like 删除时自动更新 Post 的 `likeCount`

### 2. Bookmark (聚合根)

**核心约束**：
- 每个 Bookmark 属于一个用户 (`userId: UserId`)
- 每个 Bookmark 关联一个 Post (`postId: PostId`)
- (userId, postId) 复合唯一约束：一个用户只能收藏一个Post一次
- 记录收藏时间 (`createdAt: Long`)

```kotlin
@JvmInline
value class BookmarkId(val value: String)

data class Bookmark(
    val id: BookmarkId,
    val userId: UserId,
    val postId: PostId,
    val createdAt: Long
)
```

**业务规则**：
- Bookmark 不能重复（通过复合唯一约束保证）
- Bookmark 创建时必须验证 Post 存在
- Bookmark 删除不影响 Post 的任何统计（仅用于用户管理）

### 3. 扩展 PostDetailResponse

添加当前用户的交互状态（仅在用户认证时返回）：

```kotlin
@Serializable
data class PostDetailResponse(
    val id: String,
    val content: String,
    val media: List<MediaDto>,
    val parentId: String?,              // 父Post ID，用于区分是顶层Post还是回复
    val isTopLevelPost: Boolean,        // 为true表示顶层Post，为false表示回复
    val createdAt: Long,
    val updatedAt: Long,
    val author: AuthorDto,
    val stats: StatsDto,
    val parentPost: PostSummaryResponse? = null,

    // 新增：当前用户的交互状态（认证用户可见）
    val isLikedByCurrentUser: Boolean? = null,
    val isBookmarkedByCurrentUser: Boolean? = null
)
```

**字段说明**：
- `parentId`: 如果为 null，则是顶层 Post；如果不为 null，则是某个 Post 的回复
- `isTopLevelPost`: 冗余字段用于明确表示是否为顶层 Post（便于客户端处理）
- `isLikedByCurrentUser`: null 表示未认证用户，true/false 表示认证用户是否已点赞
- `isBookmarkedByCurrentUser`: null 表示未认证用户，true/false 表示认证用户是否已收藏

---

## 数据库设计

### 1. LikesTable

```kotlin
object LikesTable : Table("likes") {
    val id = varchar("id", 36)                          // Like ID (UUID)
    val userId = varchar("user_id", 36)                 // 外键指向 users 表
    val postId = varchar("post_id", 36)                 // 外键指向 posts 表
    val createdAt = long("created_at")

    // 复合主键 + 唯一约束
    override val primaryKey = PrimaryKey(id)

    // 复合唯一约束：同一用户不能对同一Post重复点赞
    init {
        uniqueIndex("uk_user_post_like", userId, postId)
    }
}
```

**设计决策**：
- 使用 UUID 作为 Like 的主键（简化并发操作）
- (userId, postId) 复合唯一约束防止重复点赞
- 包含 `createdAt` 支持未来的排序需求（如"最近点赞"）
- 没有外键约束（Exposed 可选，提高写入性能）

### 2. BookmarksTable

```kotlin
object BookmarksTable : Table("bookmarks") {
    val id = varchar("id", 36)                          // Bookmark ID (UUID)
    val userId = varchar("user_id", 36)                 // 外键指向 users 表
    val postId = varchar("post_id", 36)                 // 外键指向 posts 表
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    // 复合唯一约束：同一用户不能重复收藏同一Post
    init {
        uniqueIndex("uk_user_post_bookmark", userId, postId)
    }
}
```

**设计决策**：
- 与 LikesTable 结构相同
- Bookmark 是用户私有数据，不需要显示公开计数

### 3. PostsTable 现有字段（已确认）

```kotlin
object PostsTable : Table("posts") {
    val id = varchar("id", 36)
    val authorId = varchar("author_id", 36)
    val content = varchar("content", 280)
    val parentId = varchar("parent_id", 36).nullable()  // ✅ 已有：用于区分顶层Post和回复
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val replyCount = integer("reply_count").default(0)
    val likeCount = integer("like_count").default(0)    // ✅ 已有：点赞计数
    val viewCount = integer("view_count").default(0)
    val bookmarkCount = integer("bookmark_count").default(0)  // 新增：收藏计数

    override val primaryKey = PrimaryKey(id)
}
```

**修改说明**：
- `parentId` 已有，用于区分顶层 Post 和回复
- `likeCount` 已有，通过 Like 表维护
- `bookmarkCount` 新增，通过 Bookmark 表维护

---

## Repository 接口设计

### 扩展 PostRepository 接口

在现有 `PostRepository` 接口基础上添加以下方法：

```kotlin
interface PostRepository {
    // 现有方法（略）

    // ========== Like 相关方法 ==========

    /**
     * 用户点赞Post
     * @return Either<LikeError, PostStats> - 成功返回更新后的统计信息
     *
     * 可能的错误:
     * - PostNotFound(postId) - Post不存在
     * - AlreadyLiked(userId, postId) - 已经点赞过
     */
    suspend fun likePost(userId: UserId, postId: PostId): Either<LikeError, PostStats>

    /**
     * 用户取消点赞
     * @return Either<LikeError, PostStats> - 成功返回更新后的统计信息
     *
     * 可能的错误:
     * - PostNotFound(postId) - Post不存在
     * - NotLiked(userId, postId) - 未曾点赞
     */
    suspend fun unlikePost(userId: UserId, postId: PostId): Either<LikeError, PostStats>

    /**
     * 检查用户是否已点赞某Post
     * @return Either<LikeError, Boolean> - true表示已点赞，false表示未点赞
     */
    suspend fun isLikedByUser(userId: UserId, postId: PostId): Either<LikeError, Boolean>

    /**
     * 获取用户已点赞的Posts列表
     * @return Flow<PostDetail> - 按创建时间倒序，支持分页
     *
     * 关键特性：
     * - 返回的PostDetail.post.parentId可用于区分顶层Post和回复
     * - 支持分页（limit/offset）
     * - 按点赞时间倒序
     */
    fun findUserLikes(userId: UserId, limit: Int, offset: Int): Flow<PostDetail>

    // ========== Bookmark 相关方法 ==========

    /**
     * 用户收藏Post
     * @return Either<BookmarkError, Unit> - 仅返回成功/失败，不更新统计
     *
     * 可能的错误:
     * - PostNotFound(postId) - Post不存在
     * - AlreadyBookmarked(userId, postId) - 已经收藏过
     */
    suspend fun bookmarkPost(userId: UserId, postId: PostId): Either<BookmarkError, Unit>

    /**
     * 用户取消收藏
     * @return Either<BookmarkError, Unit>
     *
     * 可能的错误:
     * - PostNotFound(postId) - Post不存在
     * - NotBookmarked(userId, postId) - 未曾收藏
     */
    suspend fun unbookmarkPost(userId: UserId, postId: PostId): Either<BookmarkError, Unit>

    /**
     * 检查用户是否已收藏某Post
     * @return Either<BookmarkError, Boolean> - true表示已收藏，false表示未收藏
     */
    suspend fun isBookmarkedByUser(userId: UserId, postId: PostId): Either<BookmarkError, Boolean>

    /**
     * 获取用户已收藏的Posts列表
     * @return Flow<PostDetail> - 按创建时间倒序，支持分页
     *
     * 关键特性：
     * - 返回的PostDetail.post.parentId可用于区分顶层Post和回复
     * - 支持分页（limit/offset）
     * - 按收藏时间倒序
     */
    fun findUserBookmarks(userId: UserId, limit: Int, offset: Int): Flow<PostDetail>
}
```

### 错误定义

```kotlin
sealed interface LikeError {
    data class PostNotFound(val postId: PostId) : LikeError
    data class AlreadyLiked(val userId: UserId, val postId: PostId) : LikeError
    data class NotLiked(val userId: UserId, val postId: PostId) : LikeError
    data class DatabaseError(val reason: String) : LikeError
}

sealed interface BookmarkError {
    data class PostNotFound(val postId: PostId) : BookmarkError
    data class AlreadyBookmarked(val userId: UserId, val postId: PostId) : BookmarkError
    data class NotBookmarked(val userId: UserId, val postId: PostId) : BookmarkError
    data class DatabaseError(val reason: String) : BookmarkError
}
```

---

## Use Cases 设计

### 1. LikePostUseCase

```kotlin
class LikePostUseCase(private val postRepository: PostRepository) {
    /**
     * 用户点赞Post
     *
     * 业务规则：
     * 1. 验证Post存在
     * 2. 验证用户未曾点赞
     * 3. 创建Like聚合根
     * 4. 更新PostStats.likeCount
     * 5. 返回更新后的统计信息
     */
    suspend fun execute(userId: UserId, postId: PostId): Either<LikeError, PostStats> {
        return postRepository.likePost(userId, postId)
    }
}
```

**职责**：
- 编排业务规则
- 调用 PostRepository 实现 Like 逻辑

### 2. UnlikePostUseCase

```kotlin
class UnlikePostUseCase(private val postRepository: PostRepository) {
    suspend fun execute(userId: UserId, postId: PostId): Either<LikeError, PostStats> {
        return postRepository.unlikePost(userId, postId)
    }
}
```

### 3. BookmarkPostUseCase

```kotlin
class BookmarkPostUseCase(private val postRepository: PostRepository) {
    /**
     * 用户收藏Post
     *
     * 业务规则：
     * 1. 验证Post存在
     * 2. 验证用户未曾收藏
     * 3. 创建Bookmark聚合根
     * 4. 更新PostStats.bookmarkCount（如果需要显示）
     */
    suspend fun execute(userId: UserId, postId: PostId): Either<BookmarkError, Unit> {
        return postRepository.bookmarkPost(userId, postId)
    }
}
```

### 4. UnbookmarkPostUseCase

```kotlin
class UnbookmarkPostUseCase(private val postRepository: PostRepository) {
    suspend fun execute(userId: UserId, postId: PostId): Either<BookmarkError, Unit> {
        return postRepository.unbookmarkPost(userId, postId)
    }
}
```

### 5. GetUserLikesUseCase

```kotlin
class GetUserLikesUseCase(private val postRepository: PostRepository) {
    /**
     * 获取用户已点赞的Posts列表
     *
     * 关键特性：
     * - 返回PostDetail列表，包含parentId字段
     * - 客户端可通过parentId判断是顶层Post还是回复
     * - 支持分页
     */
    fun execute(userId: UserId, limit: Int, offset: Int): Flow<PostDetail> {
        return postRepository.findUserLikes(userId, limit, offset)
    }
}
```

**重要**：
- 返回的 PostDetail 包含 `post.parentId` 字段
- 客户端可区分顶层 Post 和回复 Post

### 6. GetUserBookmarksUseCase

```kotlin
class GetUserBookmarksUseCase(private val postRepository: PostRepository) {
    /**
     * 获取用户已收藏的Posts列表
     *
     * 关键特性：
     * - 返回PostDetail列表，包含parentId字段
     * - 客户端可通过parentId判断是顶层Post还是回复
     * - 支持分页
     */
    fun execute(userId: UserId, limit: Int, offset: Int): Flow<PostDetail> {
        return postRepository.findUserBookmarks(userId, limit, offset)
    }
}
```

---

## API 路由设计

### 性能优化说明：Timeline 交互状态查询

**设计变更**：Timeline 列表接口现在返回当前用户的交互状态（点赞/收藏），通过批量查询避免 N+1 问题。

**实现方式**：
- Timeline 查询后，使用 **2 条额外 SQL 查询**（恒定数，不是 N）
- 第一条：批量检查点赞状态 `SELECT post_id FROM likes WHERE user_id = ? AND post_id IN (...)`
- 第二条：批量检查收藏状态 `SELECT post_id FROM bookmarks WHERE user_id = ? AND post_id IN (...)`
- 未认证用户返回 `null`（保证向后兼容）

**效果**：
- ✅ 时间线列表直接展示用户的点赞/收藏状态（UI 正确）
- ✅ 避免 N+1 查询（性能最优）
- ✅ 恒定 SQL 数（与列表长度无关）

### 认证路由（需要 JWT Token）

#### 1. 点赞 Post

```
POST /v1/posts/{postId}/like
Authorization: Bearer <token>
Content-Type: application/json

响应成功 (200 OK):
{
  "stats": {
    "replyCount": 5,
    "likeCount": 42,
    "viewCount": 100
  }
}

可能的错误:
- 404 Not Found: Post不存在
- 409 Conflict: 已经点赞过（AlreadyLiked）
- 500 Internal Server Error: 数据库错误
```

**实现细节**：
- 提取 `postId` 从 URL 路径
- 从 JWT Token 获取 `currentUserId`
- 调用 `LikePostUseCase.execute(currentUserId, postId)`
- 映射结果到 HTTP 响应

#### 2. 取消点赞

```
DELETE /v1/posts/{postId}/like
Authorization: Bearer <token>

响应成功 (200 OK):
{
  "stats": {
    "replyCount": 5,
    "likeCount": 41,
    "viewCount": 100
  }
}

可能的错误:
- 404 Not Found: Post不存在
- 409 Conflict: 未曾点赞（NotLiked）
```

#### 3. 收藏 Post

```
POST /v1/posts/{postId}/bookmark
Authorization: Bearer <token>

响应成功 (200 OK):
{
  "message": "Post bookmarked successfully"
}

可能的错误:
- 404 Not Found: Post不存在
- 409 Conflict: 已经收藏过（AlreadyBookmarked）
```

#### 4. 取消收藏

```
DELETE /v1/posts/{postId}/bookmark
Authorization: Bearer <token>

响应成功 (200 OK):
{
  "message": "Post unbookmarked successfully"
}

可能的错误:
- 404 Not Found: Post不存在
- 409 Conflict: 未曾收藏（NotBookmarked）
```

### 公开路由（无需认证，但可选认证）

#### 5. 获取用户已点赞的 Posts

```
GET /v1/users/{userId}/likes?limit=20&offset=0
Authorization: (可选)

响应成功 (200 OK):
{
  "posts": [
    {
      "id": "post-id-1",
      "content": "...",
      "parentId": null,          // 顶层Post
      "isTopLevelPost": true,
      "stats": {...},
      "author": {...},
      "createdAt": 1234567890,
      "isLikedByCurrentUser": true,      // 当前用户是否点赞（若已认证）
      "isBookmarkedByCurrentUser": false
    },
    {
      "id": "post-id-2",
      "content": "...",
      "parentId": "parent-post-id",     // 回复Post
      "isTopLevelPost": false,
      "stats": {...},
      "author": {...},
      "createdAt": 1234567889,
      "isLikedByCurrentUser": null,     // 未认证用户返回null
      "isBookmarkedByCurrentUser": null
    }
  ],
  "hasMore": true,
  "total": 150
}
```

**关键特性**：
- `parentId` 字段用于区分顶层 Post 和回复
- `isTopLevelPost` 冗余字段明确表示类型
- 认证用户返回 `isLikedByCurrentUser` 和 `isBookmarkedByCurrentUser`
- 未认证用户这两个字段为 null
- 支持分页（limit/offset）
- 按点赞时间倒序

#### 6. 获取用户已收藏的 Posts

```
GET /v1/users/{userId}/bookmarks?limit=20&offset=0
Authorization: (可选)

响应成功 (200 OK):
{
  "posts": [
    {
      "id": "post-id-1",
      "parentId": null,
      "isTopLevelPost": true,
      "...(同上)"
    }
  ],
  "hasMore": true
}
```

**关键特性**：
- 与 `/likes` 端点结构相同
- `parentId` 和 `isTopLevelPost` 字段清晰区分 Post 类型
- 便于客户端在 UI 中区分显示

---

## 数据流示例

### 点赞流程

```
Client Request (HTTP POST)
    ↓
POST /v1/posts/{postId}/like
Authorization: Bearer <token>
    ↓
AuthRoutes.kt (Transport Layer)
    - JWT 验证 → UserPrincipal (获取 userId)
    - 提取 postId 从路径
    - 调用 LikePostUseCase.execute(userId, postId)
    ↓
LikePostUseCase (Application Service)
    - 验证业务规则（Post存在、未曾点赞等）
    - 调用 PostRepository.likePost(userId, postId)
    ↓
ExposedPostRepository (Infrastructure)
    - 检查 Post 是否存在
    - 在 LikesTable 插入 (userId, postId)
    - 更新 PostsTable.likeCount += 1
    - 返回 Either<LikeError, PostStats>
    ↓
AuthRoutes.kt
    - 映射 LikeError → HTTP Status 或返回成功响应
    - 序列化 StatsDto
    - 返回 JSON
    ↓
Client Response (JSON)
```

### 获取用户已点赞 Posts 流程

```
Client Request
    ↓
GET /v1/users/{userId}/likes?limit=20&offset=0
    ↓
AuthRoutes.kt (Transport Layer)
    - 可选认证（提取 currentUserId）
    - 验证 userId 有效
    - 调用 GetUserLikesUseCase.execute(userId, limit, offset)
    ↓
GetUserLikesUseCase (Application Service)
    - 调用 PostRepository.findUserLikes(userId, limit, offset)
    ↓
ExposedPostRepository (Infrastructure)
    - 联接 LikesTable + PostsTable + UsersTable + MediaTable
    - 查询语句：
      SELECT posts.*, users.*, likes.created_at
      FROM likes
      JOIN posts ON likes.post_id = posts.id
      JOIN users ON posts.author_id = users.id
      LEFT JOIN media ON posts.id = media.post_id
      WHERE likes.user_id = ?
      ORDER BY likes.created_at DESC
      LIMIT ? OFFSET ?
    - 构建 PostDetail 对象，包含 parentId
    - 如果当前用户已认证，检查 isLikedByCurrentUser 和 isBookmarkedByCurrentUser
    - 返回 Flow<PostDetail>
    ↓
AuthRoutes.kt
    - 收集 Flow 为 List<PostDetail>
    - 映射 PostDetail → PostDetailResponse
    - 序列化为 JSON
    - 返回 PostListResponse
    ↓
Client Response
```

### Timeline 查询流程（已优化，返回交互状态）

```
Client Request (可选认证)
    ↓
GET /v1/posts/timeline?limit=20&offset=0
Authorization: (可选) Bearer <token>
    ↓
PostRoutes.kt (Transport Layer)
    - 可选认证（提取 currentUserId）
    - 调用 GetTimelineUseCase(limit, offset)
    ↓
GetTimelineUseCase (Application Service)
    - 调用 PostRepository.findTimeline(limit, offset)
    ↓
ExposedPostRepository (Infrastructure)
    - 查询 Posts（JOIN PostsTable + UsersTable + MediaTable）
      SELECT posts.*, users.*, media.*
      FROM posts
      JOIN users ON posts.author_id = users.id
      LEFT JOIN media ON posts.id = media.post_id
      WHERE posts.parent_id IS NULL
      ORDER BY posts.created_at DESC
      LIMIT ? OFFSET ?
    - 返回 Flow<PostDetail>
    ↓
PostRoutes.kt
    - 收集 Flow 为 List<PostDetail>
    - **【新增】如果当前用户已认证，批量查询交互状态（2 条额外 SQL）：**
      1. SELECT post_id FROM likes
         WHERE user_id = ? AND post_id IN (post1, post2, ..., postN)
      2. SELECT post_id FROM bookmarks
         WHERE user_id = ? AND post_id IN (post1, post2, ..., postN)
    - 构建 Map 映射 PostId → isLiked 和 PostId → isBookmarked
    - 映射 PostDetail → PostDetailResponse，注入交互状态
    - 返回 PostListResponse
    ↓
Client Response (包含 isLikedByCurrentUser 和 isBookmarkedByCurrentUser)
```

**性能分析**：
- 总 SQL 数：1（posts）+ N（media，但通常与 posts 一起 JOIN）+ 2（if authenticated）= 恒定 3 条
- 时间复杂度：O(n)，其中 n 是 timeline 长度
- 无 N+1 查询问题
- 批量 IN 查询性能最优

---

## Post 与 Like/Bookmark 的关系

### 设计哲学

遵循现有设计：
> 每个用户的发帖回复都属于 Post，但要区分是自己独立的 Post 还是属于回复的 Post

### 区分方式

#### 1. 使用 `parentId` 字段

```
- 顶层Post:  parentId = null
- 回复Post:  parentId = <某个Post的ID>
```

#### 2. 在 PostDetailResponse 中显式标记

```kotlin
data class PostDetailResponse(
    val parentId: String?,           // null = 顶层，非null = 回复
    val isTopLevelPost: Boolean      // 冗余字段，true = 顶层，false = 回复
)
```

#### 3. 获取用户已点赞/已收藏列表时

返回的 PostDetail 包含：
- `post.parentId`: 用于区分
- `post.content`: 完整内容
- `author`: 作者信息

客户端可根据 `parentId` 进行不同的 UI 处理：
```typescript
if (post.parentId === null) {
  // 显示为顶层Post
  renderTopLevelPost(post)
} else {
  // 显示为回复（可选展示父Post摘要）
  renderReplyPost(post, post.parentId)
}
```

### 点赞和收藏对顶层Post和回复的适用

**Like**：
- ✅ 可对顶层 Post 点赞
- ✅ 可对回复 Post 点赞
- 在用户已点赞列表中，会混合显示顶层 Post 和回复 Post

**Bookmark**：
- ✅ 可收藏顶层 Post
- ✅ 可收藏回复 Post
- 在用户已收藏列表中，会混合显示顶层 Post 和回复 Post

---

## 错误处理

### LikeError 映射

| LikeError | HTTP Status | ErrorResponse |
|-----------|-------------|---------------|
| PostNotFound | 404 Not Found | `{"error": "Post not found"}` |
| AlreadyLiked | 409 Conflict | `{"error": "Post already liked"}` |
| NotLiked | 409 Conflict | `{"error": "Post not liked"}` |
| DatabaseError | 500 | `{"error": "Internal server error"}` |

### BookmarkError 映射

| BookmarkError | HTTP Status | ErrorResponse |
|---------------|-------------|---------------|
| PostNotFound | 404 Not Found | `{"error": "Post not found"}` |
| AlreadyBookmarked | 409 Conflict | `{"error": "Post already bookmarked"}` |
| NotBookmarked | 409 Conflict | `{"error": "Post not bookmarked"}` |
| DatabaseError | 500 | `{"error": "Internal server error"}` |

### Transport 层错误映射示例

```kotlin
private fun likeError(error: LikeError): Pair<HttpStatusCode, ErrorResponse> = when (error) {
    is LikeError.PostNotFound -> HttpStatusCode.NotFound to ErrorResponse("Post not found")
    is LikeError.AlreadyLiked -> HttpStatusCode.Conflict to ErrorResponse("Post already liked")
    is LikeError.NotLiked -> HttpStatusCode.Conflict to ErrorResponse("Post not liked")
    is LikeError.DatabaseError -> HttpStatusCode.InternalServerError to ErrorResponse("Internal server error")
}

route("posts/{postId}/like") {
    post {
        val postId = call.parameters["postId"]!!
        val result = likePostUseCase.execute(userId, PostId(postId))

        when (result) {
            is Either.Right -> {
                val stats = result.value.toDto()
                call.respond(HttpStatusCode.OK, mapOf("stats" to stats))
            }
            is Either.Left -> {
                val (status, error) = likeError(result.value)
                call.respond(status, error)
            }
        }
    }
}
```

---

## 未来扩展

### 1. 通知系统

当用户点赞某个 Post 时：
- 向订阅该 Post 的用户发送 `post_liked` SSE 实时通知
- 包含最新点赞数和点赞用户信息
- 实现：`BroadcastPostLikedUseCase`，在 `LikeRoutes` 中异步调用

当用户取消点赞某个 Post 时：
- 向订阅该 Post 的用户发送 `post_unliked` SSE 实时通知
- 包含最新点赞数和 `isLiked=false`
- 实现：`BroadcastPostUnlikedUseCase`，在 `LikeRoutes` 中异步调用

```kotlin
class LikePostUseCase(
    private val postRepository: PostRepository
) {
    suspend fun execute(userId: UserId, postId: PostId): Either<LikeError, PostStats> {
        return postRepository.likePost(userId, postId)
        // 点赞成功后，由 LikeRoutes 异步调用 BroadcastPostLikedUseCase 推送 SSE 通知
    }
}
```

### 2. 热门 Post 推荐

基于 Like 数量推荐：
- 创建 RecommendationService
- 查询 Like 数最多的 Post
- 集成到 GetTimelineUseCase

### 3. 用户兴趣分析

基于用户的 Like 和 Bookmark 历史：
- 分析用户兴趣（内容分类、作者偏好等）
- 用于个性化推荐算法

### 4. Like 统计分析

- 创建 LikeAnalyticsService
- 追踪 Post 的 Like 趋势
- 支持时间维度的分析（今日、本周、本月）

### 5. Batch 操作

- 批量点赞：一次请求对多个 Post 点赞
- 批量收藏：一次请求收藏多个 Post
- 减少网络请求数

### 6. Like 实时更新（已实现）

- 点赞时推送 `post_liked` SSE 事件给订阅者
- 取消点赞时推送 `post_unliked` SSE 事件给订阅者
- 客户端实时更新 UI（点赞数 + 按钮状态）
- 详见 `realtime-notification-design.md`

---

## 实现清单

### Domain Layer
- [ ] Like 值对象和聚合根
- [ ] Bookmark 值对象和聚合根
- [ ] LikeError 和 BookmarkError 定义
- [ ] PostRepository 接口扩展（6个新方法）

### Use Cases
- [ ] LikePostUseCase
- [ ] UnlikePostUseCase
- [ ] BookmarkPostUseCase
- [ ] UnbookmarkPostUseCase
- [ ] GetUserLikesUseCase
- [ ] GetUserBookmarksUseCase

### Infrastructure Layer
- [x] LikesTable (Exposed schema)
- [x] BookmarksTable (Exposed schema)
- [x] ExposedPostRepository 扩展实现（包含批量查询方法）
- [x] Like/Bookmark Mapping 逻辑
- [x] 更新 PostsTable 添加 bookmarkCount 字段
- [x] **批量查询方法**：`batchCheckLiked()`、`batchCheckBookmarked()`（性能优化）

### Transport Layer
- [x] 扩展 PostSchema.kt（DTO 已包含 isLikedByCurrentUser/isBookmarkedByCurrentUser 字段）
- [x] 创建 LikeRoutes.kt
- [x] 创建 BookmarkRoutes.kt
- [x] Like/Bookmark 错误映射逻辑
- [x] Mapper 函数更新
- [x] **Timeline 端点优化**：返回当前用户的交互状态（批量查询）

### DI Configuration
- [ ] DomainModule.kt 注册 6 个新 Use Cases
- [ ] DataModule.kt 无需修改（PostRepository 已有）

### Database Migration
- [ ] 创建 migration 脚本添加 LikesTable
- [ ] 创建 migration 脚本添加 BookmarksTable
- [ ] 更新 PostsTable 添加 bookmarkCount 字段

### Testing
- [ ] LikePostUseCaseTest
- [ ] UnlikePostUseCaseTest
- [ ] BookmarkPostUseCaseTest
- [ ] UnbookmarkPostUseCaseTest
- [ ] GetUserLikesUseCaseTest
- [ ] GetUserBookmarksUseCaseTest
- [ ] LikeRoutesTest
- [ ] BookmarkRoutesTest
- [ ] ExposedPostRepository 扩展方法测试

---

## 关键设计决策总结

### ✅ DO

1. **区分 Post 类型**
   - 使用 `parentId` 字段区分顶层 Post 和回复
   - 在响应中显式返回 `isTopLevelPost` 标记
   - 客户端可清晰区分处理

2. **错误作为值**
   - 使用 `Either<Error, Success>` 替代异常
   - 预期的业务错误（AlreadyLiked、NotLiked）不抛异常

3. **Repository 扩展而非替换**
   - 在 PostRepository 接口中添加 Like/Bookmark 方法
   - ExposedPostRepository 实现这些方法
   - 保持架构一致性

4. **流式分页**
   - Like/Bookmark 列表使用 `Flow<PostDetail>` 支持流式分页
   - 与现有 Post 查询风格一致

5. **统计字段冗余**
   - 在 PostsTable 中存储 `likeCount` 和 `bookmarkCount`
   - 避免 COUNT 查询，提升性能
   - 通过 Like/Bookmark 操作时同步更新

### ❌ DON'T

1. **不在 PostDetailResponse 中创建复杂的嵌套**
   - `parentPost` 字段暂不加载（避免递归）
   - 客户端通过 `parentId` 单独请求

2. **不创建单独的 LikeRepository 和 BookmarkRepository**
   - 将方法添加到现有 PostRepository 接口
   - 保持架构简洁

3. **不为 Bookmark 显示公开计数**
   - `bookmarkCount` 仅用于优化查询，不返回给客户端
   - Bookmark 是用户私有的，不应暴露统计

4. **不使用外键约束**
   - Exposed 中外键约束会降低写入性能
   - 数据一致性通过应用层保证

---

## 预留接口确认

### 已有接口（已优化）

- ✅ GET `/v1/posts/{postId}` - 获取 Post 详情（返回 isLikedByCurrentUser/isBookmarkedByCurrentUser）
- ✅ GET `/v1/posts/timeline` - **已优化**：返回当前用户的交互状态（通过批量查询，2 条额外 SQL）
- ⏳ GET `/v1/posts/{postId}/replies` - 回复列表（可扩展认证检查）
- ⏳ GET `/v1/posts/users/{userId}` - 用户的 Posts（可扩展认证检查）

### 新增接口

- 📝 POST `/v1/posts/{postId}/like` - 点赞
- 📝 DELETE `/v1/posts/{postId}/like` - 取消点赞
- 📝 POST `/v1/posts/{postId}/bookmark` - 收藏
- 📝 DELETE `/v1/posts/{postId}/bookmark` - 取消收藏
- 📝 GET `/v1/users/{userId}/likes` - 用户已点赞列表（为未来用户详情页预留）
- 📝 GET `/v1/users/{userId}/bookmarks` - 用户已收藏列表（为未来用户详情页预留）

### 用户详情页预留

- 用户详情页可使用上述接口：
  - GET `/v1/users/{userId}/likes?limit=20&offset=0` 显示用户最近点赞的内容
  - GET `/v1/users/{userId}/bookmarks?limit=20&offset=0` 显示用户最近收藏的内容
  - 可选：在用户详情模型中添加 `totalLikes`, `totalBookmarks` 计数

---

**设计完成！** 🎉

这个设计完全遵循现有的 Hexagonal Architecture，并清晰地区分了顶层 Post 和回复 Post，为未来的用户详情页预留了接口。
