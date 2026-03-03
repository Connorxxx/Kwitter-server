# Post 功能设计文档

## 架构概览

遵循 Hexagonal Architecture (Ports & Adapters) 和 DDD 原则：

```
┌─────────────────────────────────────────────────────────────┐
│                        Domain Layer                         │
│  (纯 Kotlin，无框架依赖，业务规则的唯一真相来源)               │
├─────────────────────────────────────────────────────────────┤
│  Models:                                                    │
│    - Post (聚合根)                                           │
│    - PostContent, MediaUrl (Value Objects with validation)  │
│    - PostDetail, PostStats (只读投影)                        │
│                                                             │
│  Errors (sealed interface):                                │
│    - PostError (EmptyContent, PostNotFound, etc.)          │
│                                                             │
│  Repository (Port/Interface):                              │
│    - PostRepository (定义契约，实现在 Infrastructure 层)      │
│                                                             │
│  Use Cases (Application Services):                         │
│    - CreatePostUseCase                                     │
│    - DeletePostUseCase                                     │
│    - GetPostUseCase                                        │
│    - GetTimelineUseCase                                    │
│    - GetRepliesUseCase                                     │
│    - GetUserPostsUseCase                                   │
└─────────────────────────────────────────────────────────────┘
                              ↓
                    (依赖倒置：接口在上，实现在下)
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   Infrastructure Layer                      │
│              (数据库、外部服务的具体实现)                      │
├─────────────────────────────────────────────────────────────┤
│  - ExposedPostRepository (implements PostRepository)       │
│  - PostsTable (Exposed schema)                             │
│  - MediaTable (Exposed schema)                             │
│  - Post Mapping (DAO ↔ Domain Entity)                      │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      Transport Layer                        │
│                   (HTTP API - Ktor Routes)                  │
├─────────────────────────────────────────────────────────────┤
│  - POST   /posts              (创建 Post)                   │
│  - DELETE /posts/:id          (删除 Post，仅作者)           │
│  - GET    /posts/:id          (获取 Post 详情)              │
│  - GET    /posts/:id/replies  (获取回复列表)                 │
│  - GET    /timeline           (获取时间线)                   │
│  - GET    /users/:id/posts    (获取用户 Posts)              │
└─────────────────────────────────────────────────────────────┘
```

---

## Domain Models 设计

### 1. Post (聚合根)

**核心约束**：
- 每个 Post 属于一个作者 (`authorId: UserId`)
- 可以是顶层 Post (`parentId = null`) 或回复 (`parentId != null`)
- 内容最多 280 字符 (Twitter style)
- 最多 4 个媒体附件 (图片或视频)

**类型安全**：
- `PostId`：Inline value class 防止 ID 混淆
- `PostContent`：带验证的 Value Object (空检查、长度限制)
- `MediaUrl`：带验证的 Value Object (格式、长度)

### 2. PostDetail (只读投影)

**目的**：
- 聚合 Post + Author + Stats，减少客户端 N+1 查询
- 用于 API 返回，不是持久化实体

**组成**：
```kotlin
data class PostDetail(
    val post: Post,
    val author: User,       // 嵌入作者信息
    val stats: PostStats,   // 回复数、点赞数
    val parentPost: Post?   // 如果是回复，包含父 Post
)
```

---

## Repository 接口设计

### 核心方法

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `create(post: Post)` | `Either<PostError, Post>` | 创建 Post，验证父 Post 存在性 |
| `findById(postId: PostId)` | `Either<PostError, Post>` | 查找单个 Post |
| `findDetailById(postId: PostId)` | `Either<PostError, PostDetail>` | 查找详情（含作者、统计） |
| `findByAuthor(authorId, limit, offset)` | `Flow<PostDetail>` | 用户主页的 Posts |
| `findReplies(parentId, limit, offset)` | `Flow<PostDetail>` | 某个 Post 的回复列表 |
| `findTimeline(limit, offset)` | `Flow<PostDetail>` | 全站时间线（最新 Posts） |
| `delete(postId: PostId)` | `Either<PostError, Unit>` | 删除 Post |

### 设计决策

**为何使用 `Flow` 而非 `List`？**
- 流式处理，内存友好
- 支持分页和增量加载
- 未来可扩展为实时推送 (SSE)

**为何返回 `Either` 而非抛异常？**
- 错误是业务规则的一部分 (PostNotFound 是预期的)
- 编译器强制错误处理 (`when` exhaustiveness check)
- Railway-Oriented Programming：清晰的成功/失败路径

---

## Use Cases 设计

### 1. CreatePostUseCase

**业务规则编排**：
1. 验证内容格式 (`PostContent` Value Object)
2. 验证媒体数量 (≤ 4)
3. 验证媒体 URL (`MediaUrl` Value Object)
4. 验证父 Post 存在性 (如果是回复)
5. 创建实体并持久化

**错误处理**：
- `EmptyContent`：内容为空
- `ContentTooLong`：超过 280 字符
- `TooManyMedia`：超过 4 个媒体
- `ParentPostNotFound`：回复的父 Post 不存在

### 2. DeletePostUseCase

**业务规则编排**：
1. 查询 Post，验证存在性
2. 验证当前用户是 Post 的作者（权限检查）
3. 调用 Repository 删除
4. 如果是回复，自动递减父 Post 的 `replyCount`

**错误处理**：
- `PostNotFound`：Post 不存在
- `Unauthorized`：非作者尝试删除

### 3. GetPostUseCase

**职责**：
- 查询 Post 详情 (`PostDetail`)
- 未来可扩展：记录浏览量、检查权限

### 3. GetTimelineUseCase

**职责**：
- 返回全站最新 Posts (时间倒序)
- 未来可扩展：个性化推荐算法、过滤敏感内容

### 4. GetRepliesUseCase

**职责**：
- 返回某个 Post 的回复列表
- 未来可扩展：嵌套回复树、排序策略 (最新/最热)

### 5. GetUserPostsUseCase

**职责**：
- 返回用户主页的 Posts (不包括回复)
- 未来可扩展：包含/排除回复、置顶 Post

---

## Error Handling 策略

### PostError 层级

```kotlin
sealed interface PostError {
    // 验证错误 (400 Bad Request)
    EmptyContent
    ContentTooLong(actual, max)
    InvalidMediaUrl(url)
    TooManyMedia(count)

    // 业务规则错误 (404 Not Found)
    PostNotFound(postId)
    ParentPostNotFound(parentId)

    // 权限错误 (403 Forbidden)
    Unauthorized(userId, action)

    // 基础设施错误 (500 Internal Server Error)
    MediaUploadFailed(reason)
}
```

### Transport 层映射

```kotlin
when (error) {
    is PostError.EmptyContent -> call.respond(HttpStatusCode.BadRequest, ...)
    is PostError.PostNotFound -> call.respond(HttpStatusCode.NotFound, ...)
    is PostError.Unauthorized -> call.respond(HttpStatusCode.Forbidden, ...)
}
```

---

## 数据流示例

### 创建 Post 的完整流程

```
Client Request (JSON)
    ↓
AuthRoutes.kt (Transport Layer)
    - JWT 验证 → UserPrincipal
    - 反序列化 CreatePostRequest
    - 构造 CreatePostCommand
    ↓
CreatePostUseCase (Application Service)
    - 验证业务规则
    - 创建 Post 实体
    - 调用 PostRepository.create()
    ↓
ExposedPostRepository (Infrastructure)
    - 验证父 Post 存在性
    - 插入数据库
    - 返回 Either<PostError, Post>
    ↓
AuthRoutes.kt
    - 映射 PostError → HTTP Status
    - 序列化 PostResponse
    - 返回 JSON
    ↓
Client Response (JSON)
```

---

## 未来扩展点

### 1. 点赞功能
- 新建 `Like` 聚合根
- 更新 `PostStats.likeCount`
- Repository 方法：`likePost()`, `unlikePost()`

### 2. 媒体上传
- 新建 `MediaService` (Domain Service)
- 接口：`uploadMedia(file: ByteArray): Either<PostError, MediaUrl>`
- 实现：S3/OSS/本地存储

### 3. 实时通知
- Repository 返回 `Flow<PostDetail>` 已支持流式数据
- Transport 层可接入 SSE

### 4. 推荐算法
- `GetTimelineUseCase` 注入 `RecommendationService`
- 接口：`recommend(userId: UserId): Flow<PostDetail>`

### 5. 嵌套回复树
- Post 添加 `replyPath: List<PostId>` 字段
- 使用 Materialized Path 或 Closure Table 存储树结构

---

## 关键设计原则

### ✅ DO
- 使用 Inline value class 确保类型安全 (`PostId`, `UserId`)
- Value Object 在构造时验证 (`PostContent`, `MediaUrl`)
- 错误作为值返回 (`Either<PostError, T>`)
- Repository 接口在 Domain 层，实现在 Infrastructure 层
- Use Case 编排业务规则，不包含基础设施细节

### ❌ DON'T
- Domain 层导入 Ktor/Exposed 框架代码
- 在 Route Handler 中编写业务逻辑
- 使用 `String` 或 `Int` 表示 ID (用 `PostId` 代替)
- 抛异常处理预期的业务错误 (用 `Either` 代替)
- 过度设计 (YAGNI - 等第二个实现出现再抽象)

---

## 下一步实现清单

### Infrastructure Layer
- [ ] `PostsTable` (Exposed schema)
- [ ] `MediaTable` (Exposed schema)
- [ ] `ExposedPostRepository` (implements PostRepository)
- [ ] Post DAO ↔ Domain Entity 映射

### Transport Layer
- [ ] `PostSchema.kt` (Request/Response DTOs)
- [ ] `PostRoutes.kt` (Ktor routes)
- [ ] `PostMappers.kt` (Domain ↔ DTO 转换)

### DI Configuration
- [ ] `DomainModule.kt` 注册 Use Cases
- [ ] `DataModule.kt` 注册 Repositories

### Testing
- [ ] `CreatePostUseCaseTest` (单元测试)
- [ ] `PostRepositoryTest` (集成测试)
- [ ] `PostRoutesTest` (API 测试)

---

**设计完成！** 🎉

Domain 层已完全独立，无框架依赖。Infrastructure 和 Transport 层可以随时替换实现，不影响业务逻辑。
