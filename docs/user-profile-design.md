# User Profile 功能设计文档

## 架构概览

遵循 Hexagonal Architecture (Ports & Adapters) 和 DDD 原则：

```
┌─────────────────────────────────────────────────────────────┐
│                        Domain Layer                         │
│  (纯 Kotlin，无框架依赖，业务规则的唯一真相来源)               │
├─────────────────────────────────────────────────────────────┤
│  Models:                                                    │
│    - User (聚合根，扩展: username, displayName, bio)        │
│    - Follow (关注关系实体)                                   │
│    - UserProfile, UserStats (只读投影)                      │
│                                                             │
│  Value Objects (with validation):                          │
│    - Username (唯一标识符，用于@和显示)                      │
│    - DisplayName (昵称)                                      │
│    - Bio (用户简介)                                          │
│                                                             │
│  Errors (sealed interface):                                │
│    - UserError (InvalidUsername, UserNotFound, etc.)       │
│                                                             │
│  Repository (Port/Interface):                              │
│    - UserRepository (定义契约，实现在 Infrastructure 层)    │
│                                                             │
│  Use Cases (Application Services):                         │
│    - GetUserProfileUseCase                                 │
│    - UpdateUserProfileUseCase                              │
│    - FollowUserUseCase / UnfollowUserUseCase               │
│    - GetUserFollowingUseCase / GetUserFollowersUseCase     │
│    - GetUserRepliesWithStatusUseCase (新增)                 │
└─────────────────────────────────────────────────────────────┘
                              ↓
                    (依赖倒置：接口在上，实现在下)
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   Infrastructure Layer                      │
│              (数据库、外部服务的具体实现)                      │
├─────────────────────────────────────────────────────────────┤
│  - ExposedUserRepository (implements UserRepository)       │
│  - UsersTable (Exposed schema - 扩展: username)            │
│  - FollowsTable (Exposed schema - 新增)                    │
│  - User Mapping (DAO ↔ Domain Entity)                      │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      Transport Layer                        │
│                   (HTTP API - Ktor Routes)                  │
├─────────────────────────────────────────────────────────────┤
│  - GET    /users/:id                    (获取用户资料)       │
│  - GET    /users/username/:username     (通过username获取)   │
│  - PATCH  /users/me                     (更新当前用户资料)   │
│  - POST   /users/:id/follow             (关注用户)          │
│  - DELETE /users/:id/follow             (取消关注)          │
│  - GET    /users/:id/following          (关注列表)          │
│  - GET    /users/:id/followers          (粉丝列表)          │
│  - GET    /users/:id/posts              (用户Posts)         │
│  - GET    /users/:id/replies            (用户回复)          │
│  - GET    /users/:id/likes              (用户点赞)          │
└─────────────────────────────────────────────────────────────┘
```

---

## Domain Models 设计

### 1. User (聚合根 - 扩展)

**新增字段**：
- `username: Username` - 唯一标识符，用于 @ 和显示（3-20字符，字母/数字/下划线，不区分大小写）
- `displayName: DisplayName` - 昵称（1-64字符）
- `bio: Bio` - 用户简介（最大160字符）

**类型安全**：
- `Username`：Inline value class，规范化为小写，数据库 unique index
- `DisplayName`：Inline value class，带验证（长度、非空）
- `Bio`：Inline value class，带验证（长度限制）

**设计约束**：
```kotlin
@JvmInline
value class Username private constructor(val value: String) {
    companion object {
        operator fun invoke(value: String): Either<UserError, Username> {
            val normalized = value.trim().lowercase() // 规范化
            // 验证: 3-20字符，只允许字母/数字/下划线
        }
    }
}
```

### 2. Follow (关注关系实体)

**核心约束**：
- `followerId` 关注 `followingId`
- 不允许自己关注自己（业务规则在 UseCase 层验证）
- 数据库层面确保唯一性（composite unique index on (follower_id, following_id)）

```kotlin
data class Follow(
    val followerId: UserId,
    val followingId: UserId,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(followerId != followingId) { "用户不能关注自己" }
    }
}
```

### 3. UserProfile (聚合视图)

**目的**：
- 聚合 User + UserStats，减少客户端多次请求
- 用于 API 返回，不是持久化实体

**组成**：
```kotlin
data class UserProfile(
    val user: User,
    val stats: UserStats
)

data class UserStats(
    val userId: UserId,
    val followingCount: Int = 0,    // 我关注的人数
    val followersCount: Int = 0,    // 关注我的人数
    val postsCount: Int = 0         // 发布的顶层Posts数（不包括回复）
)
```

---

## Repository 接口设计

### 核心方法

#### 用户资料管理

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `findById(userId: UserId)` | `Either<UserError, User>` | 根据 ID 查找用户 |
| `findByUsername(username: Username)` | `Either<UserError, User>` | 根据 username 查找用户 |
| `findProfile(userId: UserId)` | `Either<UserError, UserProfile>` | 查询用户资料（含统计信息） |
| `findProfileByUsername(username)` | `Either<UserError, UserProfile>` | 通过 username 查询资料 |
| `updateProfile(userId, ...)` | `Either<UserError, User>` | 更新用户资料 |

#### 关注系统

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `follow(followerId, followingId)` | `Either<UserError, Follow>` | 关注用户 |
| `unfollow(followerId, followingId)` | `Either<UserError, Unit>` | 取消关注 |
| `isFollowing(followerId, followingId)` | `Boolean` | 检查是否正在关注 |
| `findFollowing(userId, limit, offset)` | `Flow<User>` | 获取关注列表（我关注的人） |
| `findFollowers(userId, limit, offset)` | `Flow<User>` | 获取粉丝列表（关注我的人） |
| `batchCheckFollowing(followerId, userIds)` | `Set<UserId>` | **批量查询关注状态（避免 N+1）** |

---

## Use Cases 设计

### 1. GetUserProfileUseCase

**职责**：
- 查询用户资料 + 统计信息
- 如果有当前用户，附加关注状态

**输出**：
```kotlin
data class ProfileView(
    val profile: UserProfile,
    val isFollowedByCurrentUser: Boolean? = null // null = 未认证或查看自己
)
```

**设计原理**：
- Repository 已经聚合了统计信息（Following/Followers/Posts 数量）
- UseCase 只需要添加当前用户的关注状态

### 2. FollowUserUseCase / UnfollowUserUseCase

**职责**：
- 验证业务规则（不能关注/取消关注自己）
- 调用 Repository 创建/删除关注关系

**错误处理**：
- `UserError.CannotFollowSelf` - 尝试关注自己
- `UserError.AlreadyFollowing` - 已经关注（数据库唯一约束）
- `UserError.NotFollowing` - 未关注，无法取消
- `UserError.FollowTargetNotFound` - 目标用户不存在

### 3. GetUserFollowingUseCase / GetUserFollowersUseCase

**职责**：
- 查询关注/粉丝列表
- **批量查询当前用户的关注状态（避免 N+1 问题）**

**性能优化**：
```kotlin
// ❌ N+1 查询：每个用户查一次关注状态
users.forEach { user ->
    val isFollowing = userRepository.isFollowing(currentUserId, user.id) // N 次查询
}

// ✅ 批量查询：一次查询所有
val followingIds = userRepository.batchCheckFollowing(currentUserId, userIds) // 1 次查询
users.forEach { user ->
    val isFollowing = user.id in followingIds // O(1) 查找
}
```

### 4. GetUserRepliesWithStatusUseCase（新增）

**职责**：
- 查询用户发布的回复（不包括顶层 Posts）
- 如果有认证用户，批量查询交互状态（点赞/收藏）

**设计原理**：
- 复用 Post 模块的 N+1 优化模式
- 新增 PostRepository 方法：`findRepliesByAuthor(authorId, limit, offset)`

---

## 数据库设计

### 1. UsersTable（扩展）

**新增列**：
```sql
ALTER TABLE users ADD COLUMN username VARCHAR(20) NOT NULL;
CREATE UNIQUE INDEX users_username_idx ON users(username);
```

**完整 Schema**：
```kotlin
object UsersTable : Table("users") {
    val id = varchar("id", 36)
    val email = varchar("email", 128).uniqueIndex()
    val passwordHash = varchar("password_hash", 128)
    val username = varchar("username", 20).uniqueIndex()  // 新增
    val displayName = varchar("display_name", 64)
    val bio = text("bio").default("")
    val avatarUrl = varchar("avatar_url", 256).nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}
```

### 2. FollowsTable（新建）

**设计约束**：
- 组合主键：`(follower_id, following_id)` 确保唯一性
- 双向索引：优化 "我关注的人" 和 "关注我的人" 查询

```kotlin
object FollowsTable : Table("follows") {
    val followerId = varchar("follower_id", 36).references(UsersTable.id)
    val followingId = varchar("following_id", 36).references(UsersTable.id)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(followerId, followingId)

    init {
        index("idx_follows_follower", false, followerId)   // 查询 "我关注的人"
        index("idx_follows_following", false, followingId) // 查询 "关注我的人"
    }
}
```

**SQL 迁移脚本**：
```sql
CREATE TABLE follows (
    follower_id VARCHAR(36) NOT NULL REFERENCES users(id),
    following_id VARCHAR(36) NOT NULL REFERENCES users(id),
    created_at BIGINT NOT NULL,
    PRIMARY KEY (follower_id, following_id)
);

CREATE INDEX idx_follows_follower ON follows(follower_id);
CREATE INDEX idx_follows_following ON follows(following_id);
```

---

## API 设计

### 公开路由（可选认证）

| 端点 | 方法 | 认证 | 参数 | 响应 |
|------|------|------|------|------|
| `/v1/users/{userId}` | GET | 可选 | - | `UserProfileResponse` |
| `/v1/users/username/{username}` | GET | 可选 | - | `UserProfileResponse` |
| `/v1/users/{userId}/following` | GET | 可选 | `limit`, `offset` | `UserListResponse` |
| `/v1/users/{userId}/followers` | GET | 可选 | `limit`, `offset` | `UserListResponse` |
| `/v1/users/{userId}/posts` | GET | 可选 | `limit`, `offset` | `PostListResponse` |
| `/v1/users/{userId}/replies` | GET | 可选 | `limit`, `offset` | `PostListResponse` |
| `/v1/users/{userId}/likes` | GET | 可选 | `limit`, `offset` | `PostListResponse` |

**可选认证说明**：
- 未认证：`isFollowedByCurrentUser` / `isLikedByCurrentUser` 返回 `null`
- 已认证：返回当前用户的交互状态

### 需要认证的路由

| 端点 | 方法 | 认证 | Body | 响应 |
|------|------|------|------|------|
| `/v1/users/me` | PATCH | ✅ | `UpdateProfileRequest` | `UserDto` |
| `/v1/users/me/avatar` | POST | ✅ | `multipart/form-data` | `AvatarUploadResponse` |
| `/v1/users/me/avatar` | DELETE | ✅ | - | `{"message": "头像已删除"}` |
| `/v1/users/{userId}/follow` | POST | ✅ | - | `{"message": "关注成功"}` |
| `/v1/users/{userId}/follow` | DELETE | ✅ | - | `{"message": "取消关注成功"}` |

---

## 头像上传/下载设计

### 设计概述

头像上传复用已有的 Media 模块基础设施（`MediaStorageRepository`、本地文件存储），但有独立的业务约束：
- 仅限图片（不支持视频）
- 更小的文件大小限制（2MB vs Media 的 10MB）
- 上传后自动更新 `user.avatarUrl`
- 删除旧头像文件（替换时自动清理）

### API 端点

#### POST `/v1/users/me/avatar` — 上传头像

**请求**：`multipart/form-data`，字段名 `avatar`

```bash
curl -X POST http://localhost:8080/v1/users/me/avatar \
  -H "Authorization: Bearer <token>" \
  -F "avatar=@profile.jpg"
```

**成功响应**（201 Created）：
```json
{
  "avatarUrl": "/uploads/avatars/a1b2c3d4e5f6...32chars.jpg"
}
```

**错误响应**：

| 场景 | HTTP 状态码 | code | message |
|------|-----------|------|---------|
| 未提供文件 | 400 | `NO_FILE_PROVIDED` | 请提供头像文件 |
| 文件类型不支持 | 400 | `INVALID_FILE_TYPE` | 仅支持 image/jpeg, image/png, image/webp |
| 文件过大 | 400 | `FILE_TOO_LARGE` | 文件过大，最大 2MB |
| 上传失败 | 500 | `UPLOAD_FAILED` | 上传失败，请稍后重试 |

#### DELETE `/v1/users/me/avatar` — 删除头像

```bash
curl -X DELETE http://localhost:8080/v1/users/me/avatar \
  -H "Authorization: Bearer <token>"
```

**成功响应**（200 OK）：
```json
{
  "message": "头像已删除"
}
```

**行为**：
- 删除存储的头像文件
- 将 `user.avatarUrl` 设为 `null`
- 如果用户没有头像，仍返回 200（幂等）

#### 头像访问（静态文件服务）

头像通过已有的静态文件服务访问，无需认证：

```
GET /uploads/avatars/{filename}
```

与 Media 模块共用 Ktor 的 `staticFiles` 配置，只是子目录不同。

### 架构设计

```
POST /v1/users/me/avatar (multipart)
    ↓
UserRoutes (Transport 层)
    ↓ 解析 multipart，提取文件字节
UploadAvatarUseCase (Domain 层)
    ↓ 验证文件类型（仅图片）、大小（≤ 2MB）
    ↓ 生成安全文件名（MD5 hash）
    ↓ 委托存储
MediaStorageRepository.upload() (Infrastructure 层)
    ↓ 写入 uploads/avatars/ 目录
    ↓ 返回 UploadedMedia
UploadAvatarUseCase
    ↓ 删除旧头像文件（如果存在）
    ↓ 更新 user.avatarUrl
    ↓ 返回 Either<UserError, AvatarUrl>
UserRoutes
    ↓ 映射为 HTTP 响应
201 Created { "avatarUrl": "/uploads/avatars/..." }
```

### Domain 层设计

#### UploadAvatarUseCase

```kotlin
data class UploadAvatarCommand(
    val userId: UserId,
    val fileName: String,
    val contentType: String,
    val fileBytes: ByteArray
)

class UploadAvatarUseCase(
    private val userRepository: UserRepository,
    private val storageRepository: MediaStorageRepository,
    private val avatarConfig: AvatarConfig
) {
    suspend operator fun invoke(command: UploadAvatarCommand): Either<UserError, String> = either {
        // 1. 验证文件类型（仅图片）
        if (command.contentType !in avatarConfig.allowedTypes) {
            raise(UserError.InvalidAvatarType(
                received = command.contentType,
                allowed = avatarConfig.allowedTypes
            ))
        }

        // 2. 验证文件大小
        val fileSize = command.fileBytes.size.toLong()
        if (fileSize > avatarConfig.maxFileSize) {
            raise(UserError.AvatarTooLarge(
                size = fileSize,
                maxSize = avatarConfig.maxFileSize
            ))
        }

        // 3. 获取当前用户（验证存在 + 获取旧头像 URL）
        val currentUser = userRepository.findById(command.userId).bind()
        val oldAvatarUrl = currentUser.avatarUrl

        // 4. 生成安全文件名并上传
        val safeFileName = generateSafeFileName(command.fileBytes, command.contentType)
        val uploaded = storageRepository.upload(command.fileBytes, safeFileName)
            .mapLeft { mediaError -> UserError.AvatarUploadFailed(mediaError.toString()) }
            .bind()

        // 5. 更新 user.avatarUrl
        val avatarUrl = uploaded.url.value
        userRepository.updateProfile(
            userId = command.userId,
            avatarUrl = avatarUrl
        ).bind()

        // 6. 删除旧头像文件（忽略错误，best-effort）
        if (oldAvatarUrl != null && oldAvatarUrl != avatarUrl) {
            val oldFileName = oldAvatarUrl.substringAfterLast("/")
            storageRepository.delete(MediaId(oldFileName)) // fire-and-forget
        }

        avatarUrl
    }
}
```

#### DeleteAvatarUseCase

```kotlin
class DeleteAvatarUseCase(
    private val userRepository: UserRepository,
    private val storageRepository: MediaStorageRepository
) {
    suspend operator fun invoke(userId: UserId): Either<UserError, Unit> = either {
        val currentUser = userRepository.findById(userId).bind()
        val avatarUrl = currentUser.avatarUrl

        // 清除 avatarUrl
        userRepository.updateProfile(userId = userId, avatarUrl = "").bind()

        // 删除文件（best-effort）
        if (avatarUrl != null) {
            val fileName = avatarUrl.substringAfterLast("/")
            storageRepository.delete(MediaId(fileName))
        }
    }
}
```

#### AvatarConfig

```kotlin
data class AvatarConfig(
    val uploadDir: String = "uploads/avatars",
    val maxFileSize: Long = 2 * 1024 * 1024,  // 2MB
    val allowedTypes: Set<String> = setOf("image/jpeg", "image/png", "image/webp")
)
```

#### UserError 扩展

```kotlin
sealed interface UserError {
    // ... 现有错误类型 ...

    // 头像相关错误
    data class InvalidAvatarType(val received: String, val allowed: Set<String>) : UserError
    data class AvatarTooLarge(val size: Long, val maxSize: Long) : UserError
    data class AvatarUploadFailed(val reason: String) : UserError
}
```

### Transport 层设计

#### Route Handler

```kotlin
// 在 userRoutes 的 authenticate("auth-jwt") 块内

post("/me/avatar") {
    val userId = call.principal<UserPrincipal>()?.userId ?: run {
        call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
        return@post
    }

    val multipart = call.receiveMultipart()
    var fileProcessed = false

    var part = multipart.readPart()
    while (part != null) {
        if (part is PartData.FileItem && part.name == "avatar") {
            val command = UploadAvatarCommand(
                userId = UserId(userId),
                fileName = part.originalFileName ?: "avatar",
                contentType = part.contentType?.toString() ?: "",
                fileBytes = part.provider().readRemaining().readByteArray()
            )

            uploadAvatarUseCase(command).fold(
                ifLeft = { error ->
                    val (status, body) = error.toHttpError()
                    call.respond(status, body)
                },
                ifRight = { avatarUrl ->
                    call.respond(HttpStatusCode.Created, AvatarUploadResponse(avatarUrl))
                }
            )
            part.dispose()
            fileProcessed = true
            return@post
        }
        part.dispose()
        part = multipart.readPart()
    }

    if (!fileProcessed) {
        call.respond(
            HttpStatusCode.BadRequest,
            ApiErrorResponse("NO_FILE_PROVIDED", "请提供头像文件")
        )
    }
}

delete("/me/avatar") {
    val userId = call.principal<UserPrincipal>()?.userId ?: run {
        call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
        return@delete
    }

    deleteAvatarUseCase(UserId(userId)).fold(
        ifLeft = { error ->
            val (status, body) = error.toHttpError()
            call.respond(status, body)
        },
        ifRight = {
            call.respond(HttpStatusCode.OK, mapOf("message" to "头像已删除"))
        }
    )
}
```

#### Response DTO

```kotlin
@Serializable
data class AvatarUploadResponse(
    val avatarUrl: String
)
```

### 存储设计

#### 目录结构

```
uploads/
├── avatars/           ← 头像专用目录
│   ├── a1b2c3d4...32chars.jpg
│   └── e5f6a7b8...32chars.png
├── ...                ← 其他 media 文件（Posts 附件等）
```

#### 文件命名

复用 Media 模块的 MD5 命名策略：
- 文件名 = `MD5(fileBytes) + "." + extension`
- 天然去重：相同图片内容 → 相同文件名 → 跳过写入

#### 静态文件服务

```kotlin
// 在已有的 staticFiles 配置中追加 avatars 目录
routing {
    staticFiles("/uploads/avatars", File("uploads/avatars"))
}
```

### DI 配置

```kotlin
// 在 userModule 或 mediaModule 中注册

single {
    AvatarConfig(
        uploadDir = app.environment.config.propertyOrNull("avatar.uploadDir")?.getString()
            ?: "uploads/avatars",
        maxFileSize = app.environment.config.propertyOrNull("avatar.maxFileSize")?.getString()
            ?.toLongOrNull() ?: (2 * 1024 * 1024),
        allowedTypes = app.environment.config.propertyOrNull("avatar.allowedTypes")?.getString()
            ?.split(",")?.map { it.trim() }?.toSet()
            ?: setOf("image/jpeg", "image/png", "image/webp")
    )
}

// 头像使用独立的 MediaStorageRepository 实例（不同 uploadDir）
single(named("avatarStorage")) {
    FileSystemMediaStorageRepository(get<AvatarConfig>().uploadDir)
}

single { UploadAvatarUseCase(get(), get(named("avatarStorage")), get()) }
single { DeleteAvatarUseCase(get(), get(named("avatarStorage"))) }
```

### application.yaml 配置

```yaml
avatar:
  uploadDir: "uploads/avatars"
  maxFileSize: "2097152"  # 2MB
  allowedTypes: "image/jpeg,image/png,image/webp"
```

### 与 PATCH /users/me 的关系

**分离设计**：头像上传使用独立端点，不混入 `PATCH /users/me`。

| 操作 | 端点 | Content-Type |
|------|------|-------------|
| 修改 username/displayName/bio | `PATCH /v1/users/me` | `application/json` |
| 上传头像 | `POST /v1/users/me/avatar` | `multipart/form-data` |
| 删除头像 | `DELETE /v1/users/me/avatar` | 无 |

**原因**：
1. **协议清晰**：JSON body 和 multipart/form-data 不应混在同一端点
2. **职责单一**：文本字段更新和文件上传是不同的操作
3. **客户端简单**：Android/iOS 可以分别处理 JSON 请求和文件上传请求
4. **`PATCH /users/me` 的 `avatarUrl` 字段**：保留但由服务端内部使用，客户端不应直接设置此字段

---

## 关键设计决策

### 1. N+1 查询预防策略

#### 问题场景：
- 获取关注列表时，需要显示"我是否关注了这些人"
- 获取粉丝列表时，需要显示"我是否关注了这些粉丝"

#### 解决方案：
**批量查询关注状态** (`batchCheckFollowing`)：

```kotlin
// Repository 层实现
override suspend fun batchCheckFollowing(
    followerId: UserId,
    userIds: List<UserId>
): Set<UserId> = dbQuery {
    if (userIds.isEmpty()) return@dbQuery emptySet()

    // 一次 SQL 查询所有关注状态
    FollowsTable.selectAll()
        .where {
            (followerId eq followerId.value) and
            (followingId inList userIds.map { it.value })
        }
        .map { UserId(it[followingId]) }
        .toSet()
}

// UseCase 层使用
val users = userRepository.findFollowing(userId, limit, offset)
val followingIds = userRepository.batchCheckFollowing(currentUserId, users.map { it.id })
users.forEach { user ->
    val isFollowing = user.id in followingIds // O(1) 查找
}
```

**SQL 查询数**：
- ❌ N+1 模式：1 次查询列表 + N 次查询关注状态 = N+1 次
- ✅ 批量查询：1 次查询列表 + 1 次批量查询状态 = 2 次

### 2. 用户统计信息计算

**实现位置**：Repository 层的 `calculateUserStats` 方法

**查询策略**：
```kotlin
// 三次独立查询（可优化为单次 CTE 查询，但现在够用）
val followingCount = SELECT COUNT(*) FROM follows WHERE follower_id = ?
val followersCount = SELECT COUNT(*) FROM follows WHERE following_id = ?
val postsCount = SELECT COUNT(*) FROM posts WHERE author_id = ? AND parent_id IS NULL
```

**为什么不冗余存储**：
- Posts 数量可能频繁变化（每次发布/删除都要更新）
- Following/Followers 数量变化相对少
- 实时计算保证数据一致性，性能可接受

**未来优化方向**：
- 如果性能成为瓶颈，可以在 UsersTable 添加冗余字段
- 使用 Redis 缓存统计信息

### 3. Username 规范化策略

**问题**：防止 "Connor" 和 "connor" 被视为不同用户

**解决方案**：
```kotlin
@JvmInline
value class Username private constructor(val value: String) {
    companion object {
        operator fun invoke(value: String): Either<UserError, Username> {
            val normalized = value.trim().lowercase()  // 统一小写
            return if (normalized.matches(VALID_PATTERN)) {
                Username(normalized).right()
            } else {
                UserError.InvalidUsername("...").left()
            }
        }
    }
}
```

**数据库约束**：
- `username` 列使用 `uniqueIndex`
- 存储时统一为小写
- 查询时也转换为小写

### 4. Follow 关系的双向索引

**查询场景**：
1. "我关注的人"（Following）：`WHERE follower_id = ?`
2. "关注我的人"（Followers）：`WHERE following_id = ?`

**索引设计**：
```kotlin
index("idx_follows_follower", false, followerId)   // 场景 1
index("idx_follows_following", false, followingId) // 场景 2
```

**性能影响**：
- 无索引：全表扫描（O(n)）
- 有索引：B-Tree 查找（O(log n)）

### 5. Likes 隐私设置（预留设计）

**当前实现**：默认公开，所有人都可以看到用户的点赞列表

**未来扩展**：
```kotlin
// UsersTable 添加隐私字段
val likesPrivacy = enumerationByName("likes_privacy", 20, PrivacyLevel::class)

enum class PrivacyLevel {
    PUBLIC,   // 公开
    PRIVATE   // 仅自己可见
}

// UserRoutes.kt 添加隐私检查
if (targetUser.likesPrivacy == Private && currentUserId != userId) {
    return HttpStatusCode.Forbidden
}
```

---

## 错误处理设计

### UserError（sealed interface）

| 错误类型 | HTTP 状态码 | 说明 |
|---------|------------|------|
| `InvalidUsername(reason)` | 400 Bad Request | 用户名格式错误 |
| `UsernameAlreadyExists(username)` | 409 Conflict | 用户名已被占用 |
| `InvalidBio(reason)` | 400 Bad Request | 简介格式错误 |
| `UserNotFound(userId)` | 404 Not Found | 用户不存在 |
| `UserNotFoundByUsername(username)` | 404 Not Found | 用户不存在 |
| `CannotFollowSelf` | 400 Bad Request | 不能关注自己 |
| `AlreadyFollowing` | 409 Conflict | 已经关注该用户 |
| `NotFollowing` | 400 Bad Request | 未关注该用户 |
| `FollowTargetNotFound(userId)` | 404 Not Found | 目标用户不存在 |

### 错误处理流程

```
HTTP Request
    ↓
Route Handler (Transport 层)
    ↓ 验证请求参数
UseCase (Domain 层)
    ↓ 业务规则验证（Value Object 构造）
Repository (Infrastructure 层)
    ↓ 数据库操作
    ↓
Either<UserError, Success>
    ↓
Route Handler
    ↓ toHttpError() 映射
HTTP Response (4xx/5xx)
```

---

## 依赖注入（DI）设计

### Koin 模块定义

```kotlin
val userModule = module {
    // Repository
    single<UserRepository> { ExposedUserRepository() }

    // Use Cases
    factory { GetUserProfileUseCase(get()) }
    factory { UpdateUserProfileUseCase(get()) }
    factory { FollowUserUseCase(get()) }
    factory { UnfollowUserUseCase(get()) }
    factory { GetUserFollowingUseCase(get()) }
    factory { GetUserFollowersUseCase(get()) }
    factory { GetUserRepliesWithStatusUseCase(get()) }
}
```

### Routing 注册

```kotlin
routing {
    userRoutes(
        getUserProfileUseCase = get(),
        updateUserProfileUseCase = get(),
        followUserUseCase = get(),
        unfollowUserUseCase = get(),
        getUserFollowingUseCase = get(),
        getUserFollowersUseCase = get(),
        getUserPostsWithStatusUseCase = get(),
        getUserRepliesWithStatusUseCase = get(),
        getUserLikesWithStatusUseCase = get()
    )
}
```

---

## 测试策略

### 单元测试（Domain 层）

**Value Object 验证**：
```kotlin
@Test
fun `Username should normalize to lowercase`() {
    val result = Username("Connor")
    result.fold(
        ifLeft = { fail("Should succeed") },
        ifRight = { assertEquals("connor", it.value) }
    )
}

@Test
fun `Username should reject invalid characters`() {
    val result = Username("hello@world")
    assertTrue(result.isLeft())
}
```

**UseCase 业务逻辑**：
```kotlin
@Test
fun `should prevent following self`() = runBlocking {
    val useCase = FollowUserUseCase(mockRepository)
    val result = useCase(UserId("123"), UserId("123"))

    assertTrue(result.isLeft())
    assertEquals(UserError.CannotFollowSelf, result.leftOrNull())
}
```

### 集成测试（Infrastructure 层）

**Repository 测试**：
```kotlin
@Test
fun `follow should create follow relationship`() = runTest {
    val repo = ExposedUserRepository()
    val result = repo.follow(UserId("1"), UserId("2"))

    assertTrue(result.isRight())
    assertTrue(repo.isFollowing(UserId("1"), UserId("2")))
}
```

### API 测试（Transport 层）

**路由测试**：
```kotlin
@Test
fun `GET /users/:id should return profile`() = testApplication {
    val response = client.get("/v1/users/123")

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.body<UserProfileResponse>()
    assertEquals("123", body.user.id)
}
```

---

## 性能考虑

### 1. 数据库索引策略

| 表 | 索引 | 目的 |
|-----|------|------|
| `users` | `username` (unique) | 快速查找用户 |
| `users` | `email` (unique) | 认证查询 |
| `follows` | `(follower_id, following_id)` (PK) | 唯一性约束 |
| `follows` | `follower_id` | "我关注的人" 查询 |
| `follows` | `following_id` | "关注我的人" 查询 |

### 2. 查询优化

**批量查询关注状态**：
- 使用 `IN` 查询替代 N 次单独查询
- 时间复杂度：O(n + m)，其中 n 是 userIds 数量，m 是结果数

**JOIN 查询关注/粉丝列表**：
```sql
-- 关注列表
SELECT users.* FROM follows
JOIN users ON follows.following_id = users.id
WHERE follows.follower_id = ?

-- 粉丝列表
SELECT users.* FROM follows
JOIN users ON follows.follower_id = users.id
WHERE follows.following_id = ?
```

### 3. 分页策略

**Offset-based Pagination**（当前实现）：
```kotlin
fun findFollowing(userId: UserId, limit: Int, offset: Int): Flow<User>
```

**优点**：简单，支持跳页
**缺点**：offset 大时性能差

**未来优化**：Cursor-based Pagination
```kotlin
fun findFollowing(userId: UserId, limit: Int, after: String?): Flow<User>
```

---

## 安全考虑

### 1. 输入验证

**Value Object 层面**：
- `Username`: 只允许字母/数字/下划线，3-20字符
- `Bio`: 最大160字符
- 所有输入统一 trim + 规范化

### 2. SQL 注入防护

**使用 Exposed DSL**：
```kotlin
// ✅ 安全：参数化查询
UsersTable.selectAll().where { username eq value }

// ❌ 危险：字符串拼接（避免）
// "SELECT * FROM users WHERE username = '$value'"
```

### 3. 权限控制

**更新资料**：只能更新自己的资料
```kotlin
PATCH /v1/users/me // ✅ 使用 JWT principal.userId
PATCH /v1/users/123 // ❌ 不允许指定其他用户ID
```

**关注操作**：必须认证
```kotlin
POST /v1/users/{userId}/follow // ✅ authenticate("auth-jwt")
```

---

## 扩展性设计

### 1. 关注系统扩展

**相互关注（Mutual Follow）**：
```kotlin
suspend fun isMutualFollow(userA: UserId, userB: UserId): Boolean {
    return isFollowing(userA, userB) && isFollowing(userB, userA)
}
```

**关注推荐**：
```kotlin
fun findRecommendedUsers(userId: UserId): Flow<User> {
    // 推荐逻辑：
    // 1. 我关注的人也关注的人（二度人脉）
    // 2. 相似兴趣的用户
}
```

### 2. 隐私设置扩展

**未来添加字段**：
```kotlin
data class User(
    // ...
    val likesPrivacy: PrivacyLevel = PrivacyLevel.PUBLIC,
    val followingPrivacy: PrivacyLevel = PrivacyLevel.PUBLIC
)
```

### 3. 通知系统集成

**关注事件**：
```kotlin
// Domain Event
sealed interface UserEvent {
    data class UserFollowed(val followerId: UserId, val followingId: UserId) : UserEvent
}

// 发送通知
eventBus.publish(UserEvent.UserFollowed(followerId, followingId))
```

---

## 架构合规性检查表

| 原则 | 实现 | 说明 |
|------|------|------|
| **Domain 层纯净** | ✅ | 无 Ktor/Exposed 依赖 |
| **依赖倒置** | ✅ | UserRepository 接口在 Domain 层 |
| **错误作为值** | ✅ | Either<UserError, T> |
| **类型安全** | ✅ | Username, Bio, DisplayName 都是 Value Objects |
| **避免 N+1** | ✅ | batchCheckFollowing 批量查询 |
| **Flow 流式处理** | ✅ | findFollowing, findFollowers |
| **薄 Transport 层** | ✅ | Routes 只做协议转换 |
| **单一职责** | ✅ | UseCase 职责明确 |
| **可测试性** | ✅ | Domain 层易于单元测试 |
| **数据库索引** | ✅ | 双向索引优化查询 |
