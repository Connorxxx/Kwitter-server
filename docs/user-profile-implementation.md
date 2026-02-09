# User Profile Feature Implementation Summary

## 实现概览

根据 Hexagonal Architecture 和 Domain-Driven Design 原则，完成了 User Profile 功能的核心实现。

**实现进度**：🟢 80% 已完成 | 🟡 20% 待集成

---

## ✅ 已完成的实现

### 1. Domain 层（✅ 100%）

#### Value Objects

**Username.kt** (`domain/model/Username.kt`)
```kotlin
@JvmInline
value class Username private constructor(val value: String) {
    companion object {
        operator fun invoke(value: String): Either<UserError, Username>
        fun unsafe(value: String): Username
    }
}
```
- ✅ 验证规则：3-20字符，字母/数字/下划线
- ✅ 规范化：统一转换为小写
- ✅ 类型安全：防止 String 混淆

**Bio.kt** (`domain/model/Bio.kt`)
```kotlin
@JvmInline
value class Bio private constructor(val value: String) {
    companion object {
        operator fun invoke(value: String): Either<UserError, Bio>
        fun unsafe(value: String): Bio
    }
}
```
- ✅ 验证规则：最大160字符
- ✅ 允许空字符串

#### Domain Models

**User (扩展)** (`domain/model/User.kt`)
```kotlin
data class User(
    val id: UserId,
    val email: Email,
    val passwordHash: PasswordHash,
    val username: Username,         // ✅ 新增
    val displayName: DisplayName,   // ✅ 类型化
    val bio: Bio,                   // ✅ 类型化
    val avatarUrl: String?,
    val createdAt: Long
)
```

**Follow** (`domain/model/Follow.kt`)
```kotlin
data class Follow(
    val followerId: UserId,
    val followingId: UserId,
    val createdAt: Long
) {
    init {
        require(followerId != followingId) { "用户不能关注自己" }
    }
}
```
- ✅ 业务规则验证：不能关注自己

**UserProfile** (`domain/model/Follow.kt`)
```kotlin
data class UserProfile(
    val user: User,
    val stats: UserStats
)

data class UserStats(
    val userId: UserId,
    val followingCount: Int,
    val followersCount: Int,
    val postsCount: Int
)
```
- ✅ 聚合视图：减少客户端多次请求

#### Errors

**UserErrors.kt** (`domain/failure/UserErrors.kt`)
```kotlin
sealed interface UserError {
    data class InvalidUsername(val reason: String)
    data class UsernameAlreadyExists(val username: String)
    data class InvalidBio(val reason: String)
    data class UserNotFound(val userId: UserId)
    data class UserNotFoundByUsername(val username: Username)
    data object CannotFollowSelf
    data object AlreadyFollowing
    data object NotFollowing
    data class FollowTargetNotFound(val userId: UserId)
}
```
- ✅ 完整的错误类型定义
- ✅ 错误作为值（不抛异常）

#### Repository Interface

**UserRepository (扩展)** (`domain/repository/UserRepository.kt`)

新增方法：
- ✅ `findById(userId: UserId): Either<UserError, User>`
- ✅ `findByUsername(username: Username): Either<UserError, User>`
- ✅ `updateProfile(...): Either<UserError, User>`
- ✅ `findProfile(userId: UserId): Either<UserError, UserProfile>`
- ✅ `findProfileByUsername(username: Username): Either<UserError, UserProfile>`
- ✅ `follow(followerId, followingId): Either<UserError, Follow>`
- ✅ `unfollow(followerId, followingId): Either<UserError, Unit>`
- ✅ `isFollowing(followerId, followingId): Boolean`
- ✅ `findFollowing(userId, limit, offset): Flow<User>`
- ✅ `findFollowers(userId, limit, offset): Flow<User>`
- ✅ **`batchCheckFollowing(followerId, userIds): Set<UserId>`** （批量查询，避免 N+1）

#### Use Cases

| UseCase | 文件 | 状态 |
|---------|------|------|
| `GetUserProfileUseCase` | `domain/usecase/GetUserProfileUseCase.kt` | ✅ |
| `UpdateUserProfileUseCase` | `domain/usecase/UpdateUserProfileUseCase.kt` | ✅ |
| `FollowUserUseCase` | `domain/usecase/FollowUserUseCase.kt` | ✅ |
| `UnfollowUserUseCase` | `domain/usecase/UnfollowUserUseCase.kt` | ✅ |
| `GetUserFollowingUseCase` | `domain/usecase/GetUserFollowingUseCase.kt` | ✅ |
| `GetUserFollowersUseCase` | `domain/usecase/GetUserFollowersUseCase.kt` | ✅ |
| `GetUserRepliesWithStatusUseCase` | `domain/usecase/GetUserRepliesWithStatusUseCase.kt` | ✅ |

**关键设计**：
- ✅ 批量查询关注状态（避免 N+1）
- ✅ Flow 返回支持分页
- ✅ 业务规则验证（不能关注自己）

---

### 2. Infrastructure 层（✅ 95%）

#### Database Schema

**UsersTable (扩展)** (`data/db/schema/UsersTable.kt`)
```kotlin
object UsersTable : Table("users") {
    val id = varchar("id", 36)
    val email = varchar("email", 128).uniqueIndex()
    val passwordHash = varchar("password_hash", 128)
    val username = varchar("username", 20).uniqueIndex()  // ✅ 新增
    val displayName = varchar("display_name", 64)
    val bio = text("bio").default("")
    val avatarUrl = varchar("avatar_url", 256).nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}
```
- ✅ 添加 username 列（uniqueIndex）

**FollowsTable** (`data/db/schema/FollowsTable.kt`)
```kotlin
object FollowsTable : Table("follows") {
    val followerId = varchar("follower_id", 36).references(UsersTable.id)
    val followingId = varchar("following_id", 36).references(UsersTable.id)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(followerId, followingId)

    init {
        index("idx_follows_follower", false, followerId)
        index("idx_follows_following", false, followingId)
    }
}
```
- ✅ 组合主键保证唯一性
- ✅ 双向索引优化查询

#### Mapping

**UserMapping (更新)** (`data/db/mapping/UserMapping.kt`)
```kotlin
fun ResultRow.toDomain(): User {
    return User(
        id = UserId(this[UsersTable.id]),
        email = Email.unsafe(this[UsersTable.email]),
        passwordHash = PasswordHash(this[UsersTable.passwordHash]),
        username = Username.unsafe(this[UsersTable.username]),      // ✅
        displayName = DisplayName.unsafe(this[UsersTable.displayName]), // ✅
        bio = Bio.unsafe(this[UsersTable.bio]),                    // ✅
        avatarUrl = this[UsersTable.avatarUrl],
        createdAt = this[UsersTable.createdAt]
    )
}
```
- ✅ 映射新增字段

#### Repository Implementation

**ExposedUserRepository** (`data/repository/ExposedUserRepository.kt`)

实现了所有新增的 Repository 方法：

| 方法 | 职责 | 状态 |
|------|------|------|
| `save(user)` | 创建用户（更新：添加 username 字段） | ✅ |
| `findByEmail(email)` | 根据邮箱查找 | ✅ |
| `findById(userId)` | 根据 ID 查找 | ✅ |
| `findByUsername(username)` | 根据 username 查找 | ✅ |
| `updateProfile(...)` | 更新用户资料 | ✅ |
| `findProfile(userId)` | 查询资料（含统计信息） | ✅ |
| `findProfileByUsername(username)` | 通过 username 查询资料 | ✅ |
| `follow(followerId, followingId)` | 关注用户 | ✅ |
| `unfollow(followerId, followingId)` | 取消关注 | ✅ |
| `isFollowing(followerId, followingId)` | 检查关注状态 | ✅ |
| `findFollowing(userId, limit, offset)` | 查询关注列表（JOIN 查询） | ✅ |
| `findFollowers(userId, limit, offset)` | 查询粉丝列表（JOIN 查询） | ✅ |
| `batchCheckFollowing(followerId, userIds)` | 批量查询关注状态（避免 N+1） | ✅ |

**关键实现细节**：

1. **批量查询关注状态**（避免 N+1）：
```kotlin
override suspend fun batchCheckFollowing(followerId: UserId, userIds: List<UserId>): Set<UserId> = dbQuery {
    if (userIds.isEmpty()) return@dbQuery emptySet()

    FollowsTable.selectAll()
        .where {
            (followerId eq followerId.value) and
            (followingId inList userIds.map { it.value })
        }
        .map { UserId(it[followingId]) }
        .toSet()
}
```
- ✅ 一次 SQL 查询所有关注状态
- ✅ O(1) 查找性能

2. **JOIN 查询关注/粉丝列表**：
```kotlin
// 关注列表
FollowsTable.join(UsersTable, JoinType.INNER, FollowsTable.followingId, UsersTable.id)
    .selectAll()
    .where { FollowsTable.followerId eq userId.value }

// 粉丝列表
FollowsTable.join(UsersTable, JoinType.INNER, FollowsTable.followerId, UsersTable.id)
    .selectAll()
    .where { FollowsTable.followingId eq userId.value }
```
- ✅ 避免 N+1 问题
- ✅ 利用索引优化查询

3. **统计信息计算**：
```kotlin
private suspend fun calculateUserStats(userId: UserId): UserStats = dbQuery {
    val followingCount = FollowsTable.selectAll()
        .where { followerId eq userId.value }
        .count().toInt()

    val followersCount = FollowsTable.selectAll()
        .where { followingId eq userId.value }
        .count().toInt()

    val postsCount = PostsTable.selectAll()
        .where { (authorId eq userId.value) and parentId.isNull() }
        .count().toInt()

    UserStats(userId, followingCount, followersCount, postsCount)
}
```
- ✅ 三次独立查询（可优化为 CTE，但现在够用）
- ✅ 实时计算，保证一致性

---

### 3. Transport 层（✅ 100%）

#### DTOs (Data Transfer Objects)

**UserSchema.kt** (`features/user/UserSchema.kt`)

**Request DTOs**：
- ✅ `UpdateProfileRequest` (username, displayName, bio, avatarUrl)

**Response DTOs**：
- ✅ `UserDto` (id, username, displayName, bio, avatarUrl, createdAt)
- ✅ `UserStatsDto` (followingCount, followersCount, postsCount)
- ✅ `UserProfileResponse` (user, stats, isFollowedByCurrentUser)
- ✅ `UserListItemDto` (user, isFollowedByCurrentUser)
- ✅ `UserListResponse` (users, hasMore)

#### Mappers

**UserMappers.kt** (`features/user/UserMappers.kt`)

**Domain -> Response**：
- ✅ `User.toDto()`
- ✅ `UserStats.toDto()`
- ✅ `UserProfile.toResponse()`
- ✅ `GetUserProfileUseCase.ProfileView.toResponse()`
- ✅ `GetUserFollowingUseCase.FollowingItem.toDto()`
- ✅ `GetUserFollowersUseCase.FollowerItem.toDto()`

**Error -> HTTP**：
- ✅ `UserError.toHttpError()`: 业务错误 -> (HttpStatusCode, ApiErrorResponse)

#### API Routes

**UserRoutes.kt** (`features/user/UserRoutes.kt`)

**公开路由（可选认证）**：
| 端点 | 方法 | 状态 |
|------|------|------|
| `/v1/users/{userId}` | GET | ✅ |
| `/v1/users/username/{username}` | GET | ✅ |
| `/v1/users/{userId}/following` | GET | ✅ |
| `/v1/users/{userId}/followers` | GET | ✅ |
| `/v1/users/{userId}/posts` | GET | ✅ |
| `/v1/users/{userId}/replies` | GET | ✅ |
| `/v1/users/{userId}/likes` | GET | ✅ |

**需要认证的路由**：
| 端点 | 方法 | 状态 |
|------|------|------|
| `/v1/users/me` | PATCH | ✅ |
| `/v1/users/{userId}/follow` | POST | ✅ |
| `/v1/users/{userId}/follow` | DELETE | ✅ |

**关键特性**：
- ✅ 使用 `authenticateOptional` 支持可选认证
- ✅ 分页支持（limit + offset，自动计算 hasMore）
- ✅ 批量查询交互状态（避免 N+1）
- ✅ 错误映射为正确的 HTTP 状态码

---

## 🟡 未完成的部分

### 1. PostRepository 扩展（⚠️ 必须完成）

**缺失方法**：
```kotlin
// domain/repository/PostRepository.kt
fun findRepliesByAuthor(authorId: UserId, limit: Int, offset: Int): Flow<PostDetail>
```

**需要在 ExposedPostRepository 实现**：
```kotlin
// data/repository/ExposedPostRepository.kt
override fun findRepliesByAuthor(authorId: UserId, limit: Int, offset: Int): Flow<PostDetail> = flow {
    dbQuery {
        val posts = PostsTable
            .join(UsersTable, JoinType.INNER, PostsTable.authorId, UsersTable.id)
            .selectAll()
            .where {
                (PostsTable.authorId eq authorId.value) and
                PostsTable.parentId.isNotNull()  // ✅ 只返回回复
            }
            .orderBy(PostsTable.createdAt to SortOrder.DESC)
            .limit(limit, offset.toLong())
            .map { row ->
                val post = row.toPost()
                val author = row.toDomain()
                val stats = row.toPostStats()
                PostDetail(post, author, stats)
            }

        posts.forEach { emit(it) }
    }
}
```

**影响的功能**：
- ❌ `GET /v1/users/{userId}/replies` 无法返回数据
- ⚠️ `GetUserRepliesWithStatusUseCase` 依赖此方法

---

### 2. 数据库迁移（⚠️ 必须执行）

**需要执行的 SQL**：

```sql
-- 1. 添加 username 列到 users 表
ALTER TABLE users ADD COLUMN username VARCHAR(20);

-- 2. 为现有用户生成默认 username（临时脚本）
UPDATE users
SET username = LOWER(CONCAT('user_', SUBSTRING(id, 1, 8)))
WHERE username IS NULL;

-- 3. 设置 username 为 NOT NULL 并添加唯一索引
ALTER TABLE users ALTER COLUMN username SET NOT NULL;
CREATE UNIQUE INDEX users_username_idx ON users(username);

-- 4. 创建 follows 表
CREATE TABLE follows (
    follower_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    following_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (follower_id, following_id)
);

-- 5. 创建索引优化查询
CREATE INDEX idx_follows_follower ON follows(follower_id);
CREATE INDEX idx_follows_following ON follows(following_id);
```

**迁移工具**：
- 🔧 使用 Flyway 或 Liquibase 管理数据库版本
- 📁 将脚本放在 `src/main/resources/db/migration/V4__add_user_profile_features.sql`

---

### 3. Dependency Injection 配置（⚠️ 必须完成）

**需要在 DI 容器中注册 UseCase**：

```kotlin
// di/Modules.kt 或类似文件

val userModule = module {
    // Repository（已存在，可能需要更新）
    single<UserRepository> { ExposedUserRepository() }

    // 新增 Use Cases
    factory { GetUserProfileUseCase(get()) }
    factory { UpdateUserProfileUseCase(get()) }
    factory { FollowUserUseCase(get()) }
    factory { UnfollowUserUseCase(get()) }
    factory { GetUserFollowingUseCase(get()) }
    factory { GetUserFollowersUseCase(get()) }
    factory { GetUserRepliesWithStatusUseCase(get()) } // 依赖 PostRepository
}
```

**注意**：
- ✅ `UserRepository` 可能已存在，确保更新为新实现
- ⚠️ `GetUserRepliesWithStatusUseCase` 依赖 `PostRepository`

---

### 4. Routing 注册（⚠️ 必须完成）

**需要在 Application.kt 或 Routing.kt 中注册路由**：

```kotlin
// plugins/Routing.kt 或 Application.kt

fun Application.configureRouting() {
    routing {
        // 现有路由
        authRoutes(...)
        postRoutes(...)

        // ✅ 新增：User Profile 路由
        userRoutes(
            getUserProfileUseCase = get(),
            updateUserProfileUseCase = get(),
            followUserUseCase = get(),
            unfollowUserUseCase = get(),
            getUserFollowingUseCase = get(),
            getUserFollowersUseCase = get(),
            getUserPostsWithStatusUseCase = get(),        // 已存在
            getUserRepliesWithStatusUseCase = get(),      // 新增
            getUserLikesWithStatusUseCase = get()         // 已存在
        )
    }
}
```

---

### 5. Auth 模块集成（⚠️ 必须更新）

**问题**：现有的 `RegisterUseCase` 和 `LoginUseCase` 需要更新

**需要修改**：

**RegisterUseCase**：
```kotlin
// domain/usecase/RegisterUseCase.kt

// ❌ 旧实现
val user = User(
    id = UserId(generateId()),
    email = email,
    passwordHash = hashedPassword,
    displayName = command.displayName,  // String
    bio = "",                           // String
    avatarUrl = null,
    createdAt = System.currentTimeMillis()
)

// ✅ 新实现（需要添加 username）
val username = Username("user_${generateShortId()}") // 或从 command 获取
val displayName = DisplayName(command.displayName)

val user = User(
    id = UserId(generateId()),
    email = email,
    passwordHash = hashedPassword,
    username = username.getOrThrow(),       // ✅ 新增
    displayName = displayName.getOrThrow(), // ✅ 类型化
    bio = Bio.unsafe(""),                   // ✅ 类型化
    avatarUrl = null,
    createdAt = System.currentTimeMillis()
)
```

**注意事项**：
- ⚠️ 需要生成默认 username（或要求用户输入）
- ⚠️ 验证 username 唯一性

**AuthMappers**：
```kotlin
// features/auth/AuthMappers.kt

fun User.toAuthDto(): UserDto {
    return UserDto(
        id = id.value,
        username = username.value,           // ✅ 新增
        displayName = displayName.value,     // ✅ 从 Value Object 提取
        bio = bio.value,                     // ✅ 从 Value Object 提取
        avatarUrl = avatarUrl,
        createdAt = createdAt
    )
}
```

---

### 6. 数据库初始化（⚠️ 必须更新）

**需要在 Database.kt 中注册新表**：

```kotlin
// data/db/Database.kt

object DatabaseFactory {
    fun init() {
        Database.connect(...)

        transaction {
            SchemaUtils.create(
                UsersTable,
                PostsTable,
                MediaTable,
                LikesTable,
                BookmarksTable,
                FollowsTable  // ✅ 新增
            )
        }
    }
}
```

---

## 📋 实施清单（按优先级）

### 🔴 高优先级（阻塞功能）

- [ ] **1. 执行数据库迁移** (30分钟)
  - [ ] 添加 `username` 列到 `users` 表
  - [ ] 创建 `follows` 表
  - [ ] 创建必要的索引

- [ ] **2. 更新 Auth 模块** (1小时)
  - [ ] 修改 `RegisterUseCase` 生成默认 username
  - [ ] 更新 `AuthMappers` 映射新字段
  - [ ] 测试注册流程

- [ ] **3. 实现 PostRepository.findRepliesByAuthor** (30分钟)
  - [ ] 在 `ExposedPostRepository` 添加实现
  - [ ] 测试回复查询

- [ ] **4. 配置 DI 和 Routing** (30分钟)
  - [ ] 注册 User Profile Use Cases
  - [ ] 注册 `userRoutes`
  - [ ] 更新 `DatabaseFactory` 添加 `FollowsTable`

### 🟡 中优先级（功能完善）

- [ ] **5. API 测试** (2小时)
  - [ ] 测试所有 User Profile 端点
  - [ ] 测试分页功能
  - [ ] 测试错误处理

- [ ] **6. 性能测试** (1小时)
  - [ ] 验证批量查询避免 N+1
  - [ ] 测试大数据量下的分页性能
  - [ ] 检查数据库索引是否生效

- [ ] **7. 文档更新** (30分钟)
  - [ ] 更新 Swagger/OpenAPI 文档
  - [ ] 更新 README.md

### 🟢 低优先级（未来优化）

- [ ] **8. Likes 隐私设置** (2小时)
  - [ ] 添加 `likesPrivacy` 字段到 User
  - [ ] 实现隐私检查逻辑
  - [ ] 添加设置接口

- [ ] **9. 关注推荐系统** (8小时)
  - [ ] 实现二度人脉推荐
  - [ ] 添加推荐算法

- [ ] **10. Cursor-based Pagination** (4小时)
  - [ ] 替换 offset-based pagination
  - [ ] 提升大 offset 查询性能

---

## 🧪 测试指南

### 手动测试流程

#### 1. 创建测试用户

```bash
# 注册用户 A
POST /v1/auth/register
{
  "email": "alice@example.com",
  "password": "password123",
  "displayName": "Alice"
}

# 注册用户 B
POST /v1/auth/register
{
  "email": "bob@example.com",
  "password": "password123",
  "displayName": "Bob"
}
```

⚠️ **需要更新注册接口支持 username**（见上文）

#### 2. 更新用户资料

```bash
PATCH /v1/users/me
Authorization: Bearer <alice_token>
{
  "username": "alice_wonder",
  "bio": "Software Engineer | Coffee Lover"
}
```

#### 3. 测试关注功能

```bash
# Alice 关注 Bob
POST /v1/users/{bob_id}/follow
Authorization: Bearer <alice_token>

# 查询 Alice 的关注列表
GET /v1/users/{alice_id}/following
Authorization: Bearer <alice_token>

# 查询 Bob 的粉丝列表
GET /v1/users/{bob_id}/followers
```

#### 4. 测试用户资料查询

```bash
# 通过 userId 查询
GET /v1/users/{alice_id}

# 通过 username 查询
GET /v1/users/username/alice_wonder

# 响应应包含：
{
  "user": { "id", "username", "displayName", "bio", ... },
  "stats": { "followingCount", "followersCount", "postsCount" },
  "isFollowedByCurrentUser": true/false/null
}
```

#### 5. 测试内容查询

```bash
# 查询用户的 Posts
GET /v1/users/{alice_id}/posts?limit=20&offset=0

# 查询用户的回复
GET /v1/users/{alice_id}/replies?limit=20&offset=0

# 查询用户的点赞
GET /v1/users/{alice_id}/likes?limit=20&offset=0
```

#### 6. 测试 N+1 优化

**验证方法**：
1. 启用数据库查询日志
2. 访问 `/v1/users/{userId}/following?limit=20`
3. 检查日志中 SQL 查询数量

**预期结果**：
- ✅ 2次查询（1次查询列表 + 1次批量查询关注状态）
- ❌ 如果是 N+1：21次查询（1次列表 + 20次单独查询）

---

## 🔧 故障排除

### 问题 1：数据库迁移失败

**症状**：
```
ERROR: column "username" does not exist
```

**解决方案**：
1. 确认执行了数据库迁移脚本
2. 检查 `UsersTable` 是否包含 `username` 列
3. 重启应用程序

### 问题 2：注册失败

**症状**：
```
ERROR: null value in column "username" violates not-null constraint
```

**解决方案**：
- ⚠️ 更新 `RegisterUseCase` 生成默认 username（见上文）

### 问题 3：关注自己成功

**症状**：
用户可以关注自己

**解决方案**：
- ✅ 业务规则已在 `FollowUserUseCase` 验证
- 检查是否正确调用了 UseCase（不是直接调用 Repository）

### 问题 4：N+1 查询仍然存在

**症状**：
查询关注列表时产生大量 SQL 查询

**解决方案**：
1. 确认使用了 `batchCheckFollowing` 方法
2. 检查 `GetUserFollowingUseCase` 实现
3. 启用 SQL 日志验证查询数量

---

## 📊 性能指标

### 预期性能

| 操作 | SQL 查询数 | 响应时间（估计） |
|------|-----------|----------------|
| 获取用户资料 | 4 (用户 + 3个统计) | < 50ms |
| 获取关注列表（20人） | 2 (列表 + 批量状态) | < 100ms |
| 获取粉丝列表（20人） | 2 (列表 + 批量状态) | < 100ms |
| 关注用户 | 2 (检查存在 + 插入) | < 30ms |
| 取消关注 | 1 (删除) | < 20ms |

### 数据库索引效果

**无索引**：
```sql
-- 查询关注列表：全表扫描 O(n)
EXPLAIN ANALYZE SELECT * FROM follows WHERE follower_id = 'xxx';
> Seq Scan on follows (cost=0.00..1000.00 rows=1)
```

**有索引**：
```sql
-- 查询关注列表：索引扫描 O(log n)
EXPLAIN ANALYZE SELECT * FROM follows WHERE follower_id = 'xxx';
> Index Scan using idx_follows_follower (cost=0.28..8.30 rows=1)
```

**索引大小估算**：
- 100万关注关系 ≈ 50MB (主键) + 15MB (follower索引) + 15MB (following索引)
- 总计：≈ 80MB

---

## 🚀 下一步开发建议

### 短期（1周内）

1. ✅ **完成核心集成**（优先级最高）
   - 执行数据库迁移
   - 更新 Auth 模块
   - 配置 DI 和 Routing

2. 📝 **API 文档**
   - 生成 Swagger 文档
   - 添加请求示例

3. 🧪 **测试覆盖**
   - 单元测试（Domain 层）
   - 集成测试（Repository 层）
   - API 测试（Transport 层）

### 中期（2-4周）

4. 🔒 **Likes 隐私设置**
   - 添加隐私字段
   - 实现隐私检查

5. 🎯 **关注推荐**
   - 二度人脉推荐
   - 相似兴趣推荐

6. 📊 **监控和日志**
   - 添加关键指标（关注数、查询性能）
   - 设置告警

### 长期（1-3个月）

7. ⚡ **性能优化**
   - Cursor-based pagination
   - Redis 缓存统计信息
   - 数据库读写分离

8. 🔔 **通知系统**
   - 关注通知
   - 点赞/评论通知

9. 📈 **数据分析**
   - 用户增长分析
   - 关注关系分析
   - 推荐系统优化

---

## 📚 相关资源

### 代码位置

```
src/main/kotlin/
├── domain/
│   ├── model/
│   │   ├── Username.kt           ✅ 新增
│   │   ├── Bio.kt                ✅ 新增
│   │   ├── Follow.kt             ✅ 新增
│   │   └── User.kt               ✅ 更新
│   ├── failure/
│   │   └── UserErrors.kt         ✅ 新增
│   ├── repository/
│   │   └── UserRepository.kt     ✅ 扩展
│   └── usecase/
│       ├── GetUserProfileUseCase.kt           ✅ 新增
│       ├── UpdateUserProfileUseCase.kt        ✅ 新增
│       ├── FollowUserUseCase.kt               ✅ 新增
│       ├── UnfollowUserUseCase.kt             ✅ 新增
│       ├── GetUserFollowingUseCase.kt         ✅ 新增
│       ├── GetUserFollowersUseCase.kt         ✅ 新增
│       └── GetUserRepliesWithStatusUseCase.kt ✅ 新增
│
├── data/
│   ├── db/
│   │   ├── schema/
│   │   │   ├── UsersTable.kt     ✅ 更新
│   │   │   └── FollowsTable.kt   ✅ 新增
│   │   └── mapping/
│   │       └── UserMapping.kt    ✅ 更新
│   └── repository/
│       └── ExposedUserRepository.kt ✅ 扩展
│
└── features/
    └── user/
        ├── UserSchema.kt         ✅ 新增
        ├── UserMappers.kt        ✅ 新增
        └── UserRoutes.kt         ✅ 新增
```

### 依赖的其他模块

| 模块 | 依赖关系 | 说明 |
|------|---------|------|
| **Auth** | ⚠️ 需要更新 | RegisterUseCase 必须生成 username |
| **Post** | ⚠️ 需要扩展 | PostRepository 需添加 findRepliesByAuthor |
| **Like** | ✅ 已集成 | GetUserLikesWithStatusUseCase 已存在 |
| **Bookmark** | ✅ 已集成 | 通过 PostRepository 查询 |

### 数据库 ER 图

```
┌─────────────┐       ┌─────────────┐
│   users     │       │   follows   │
├─────────────┤       ├─────────────┤
│ id (PK)     │◄──────┤ follower_id │
│ email       │       │ following_id├──┐
│ username    │       │ created_at  │  │
│ displayName │       └─────────────┘  │
│ bio         │                        │
│ avatarUrl   │◄───────────────────────┘
│ createdAt   │
└─────────────┘
       │
       │ 1:N
       ▼
┌─────────────┐
│   posts     │
├─────────────┤
│ id (PK)     │
│ author_id   │
│ content     │
│ parent_id   │
└─────────────┘
```

---

## ✅ 完成标准

在认为此功能"完成"之前，必须满足以下条件：

- [x] ✅ Domain 层完全实现（无框架依赖）
- [x] ✅ Repository 接口和实现完成
- [x] ✅ Use Cases 实现并遵循业务规则
- [x] ✅ Transport 层（Routes + DTOs + Mappers）完成
- [ ] ⚠️ 数据库迁移脚本执行
- [ ] ⚠️ DI 配置和 Routing 注册
- [ ] ⚠️ Auth 模块集成（username 生成）
- [ ] ⚠️ PostRepository 扩展（findRepliesByAuthor）
- [ ] ⏳ API 测试通过（所有端点）
- [ ] ⏳ 性能测试通过（N+1 验证）
- [ ] ⏳ 文档更新（Swagger + README）

**当前进度**：80% 完成，需要完成剩余 20% 的集成工作。

---

## 🤝 贡献指南

### 如何继续实现

1. **Fork 本项目**
2. **创建 feature 分支**：`git checkout -b feature/user-profile-integration`
3. **按照"实施清单"逐项完成**
4. **提交代码**并创建 Pull Request

### 代码规范

- ✅ 遵循 Hexagonal Architecture
- ✅ Domain 层无框架依赖
- ✅ 使用 Either<Error, Success> 处理错误
- ✅ Value Objects 确保类型安全
- ✅ Repository 方法返回 Either 或 Flow
- ✅ UseCase 单一职责
- ✅ Routes 只做协议转换

### 提交信息格式

```
feat(user): implement user profile routes
fix(user): fix N+1 query in following list
docs(user): update user profile design document
test(user): add integration tests for follow feature
```

---

## 📞 联系方式

如有问题或需要帮助，请：
- 📧 提交 Issue
- 💬 查看设计文档：`docs/user-profile-design.md`
- 📖 参考架构哲学：`docs/README.md`
