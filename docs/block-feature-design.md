# Block 功能设计文档

**更新时间**: 2026-02-13

## 架构概览

遵循 Hexagonal Architecture (Ports & Adapters) 和 DDD 原则：

```
┌─────────────────────────────────────────────────────────────┐
│                        Domain Layer                         │
│  (纯 Kotlin，无框架依赖，业务规则的唯一真相来源)               │
├─────────────────────────────────────────────────────────────┤
│  Models:                                                    │
│    - Block (拉黑关系实体)                                    │
│                                                             │
│  Errors (sealed interface):                                │
│    - UserError (CannotBlockSelf, AlreadyBlocked, etc.)     │
│    - MessageError (UserBlocked)                            │
│                                                             │
│  Repository (Port/Interface):                              │
│    - UserRepository (扩展: block, unblock, isBlocked, etc.)│
│                                                             │
│  Use Cases (Application Services):                         │
│    - BlockUserUseCase                                      │
│    - UnblockUserUseCase                                    │
│    - (扩展) FollowUserUseCase - 拉黑关系中禁止关注          │
│    - (扩展) SendMessageUseCase - 拉黑关系中禁止私信         │
│    - (扩展) Get*WithStatusUseCase - 过滤拉黑用户内容        │
│    - (扩展) GetUserProfileUseCase - 返回拉黑状态            │
│    - (扩展) GetPostDetailWithStatusUseCase - 隐藏拉黑用户帖 │
└─────────────────────────────────────────────────────────────┘
                              ↓
                    (依赖倒置：接口在上，实现在下)
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   Infrastructure Layer                      │
│              (数据库、外部服务的具体实现)                      │
├─────────────────────────────────────────────────────────────┤
│  - ExposedUserRepository (implements block methods)        │
│  - BlocksTable (Exposed schema - 新增)                     │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      Transport Layer                        │
│                   (HTTP API - Ktor Routes)                  │
├─────────────────────────────────────────────────────────────┤
│  - POST   /v1/users/{userId}/block    (拉黑用户)           │
│  - DELETE /v1/users/{userId}/block    (取消拉黑)           │
└─────────────────────────────────────────────────────────────┘
```

---

## Domain Model 设计

### Block (拉黑关系实体)

**核心约束**：
- `blockerId` 拉黑 `blockedId`（单向操作）
- 不允许自己拉黑自己（业务规则在 UseCase 层验证）
- 数据库层面确保唯一性（composite PK on (blocker_id, blocked_id)）

```kotlin
data class Block(
    val blockerId: UserId,
    val blockedId: UserId,
    val createdAt: Long = System.currentTimeMillis()
)
```

**与 Follow 的关系**：
- 拉黑时自动解除双向关注
- 拉黑关系存在时，禁止关注（任何方向）

---

## Database Schema

### BlocksTable

```sql
CREATE TABLE blocks (
    blocker_id VARCHAR(36) REFERENCES users(id),
    blocked_id VARCHAR(36) REFERENCES users(id),
    created_at BIGINT NOT NULL,
    PRIMARY KEY (blocker_id, blocked_id)
);

CREATE INDEX idx_blocks_blocker ON blocks(blocker_id);
CREATE INDEX idx_blocks_blocked ON blocks(blocked_id);
```

**设计决策**：
- 使用组合主键而非单独 ID，天然防止重复拉黑
- 双向索引：查询"我拉黑的人"和"谁拉黑了我"都高效

---

## Repository 接口设计

`UserRepository` 扩展方法：

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `block(blockerId, blockedId)` | `Either<UserError, Block>` | 拉黑用户，检查目标存在性 |
| `unblock(blockerId, blockedId)` | `Either<UserError, Unit>` | 取消拉黑 |
| `isBlocked(userId1, userId2)` | `Boolean` | **双向**检查是否存在拉黑关系 |
| `getBlockedRelationUserIds(userId)` | `Set<UserId>` | 获取所有拉黑关系用户 ID（已拉黑 + 被拉黑） |

### 为何 `isBlocked` 是双向的？

拉黑的核心语义是**隔离**：A 拉黑 B 后，A 和 B 都不应该看到对方的内容、不能互相关注和私信。因此检查时需要同时查 `A→B` 和 `B→A` 两个方向。

### 为何 `getBlockedRelationUserIds` 返回双向 union？

列表场景（时间线、回复列表等）需要一次性过滤所有拉黑关系用户。预取排除集合后，过滤操作是 O(1) 的 Set 查找。

---

## Use Cases 设计

### 1. BlockUserUseCase

**业务规则编排**：
1. 验证不能拉黑自己
2. 调用 `userRepository.block()` 创建拉黑关系
3. 自动解除双向关注（忽略 `NotFollowing` 错误）

**副作用**：
- 关注关系自动清理，无需客户端额外调用

### 2. UnblockUserUseCase

**业务规则编排**：
1. 验证不能取消拉黑自己
2. 调用 `userRepository.unblock()` 删除拉黑关系

**注意**：取消拉黑后不会自动恢复关注关系，需要用户手动重新关注。

---

## 拉黑影响范围

### 直接行为限制

| 操作 | 影响 | 实现位置 |
|------|------|---------|
| 关注 | 拉黑关系中禁止关注（双向） | `FollowUserUseCase` |
| 私信 | 拉黑关系中禁止发送消息（双向） | `SendMessageUseCase` |

### 内容过滤

| 场景 | 过滤策略 | 实现位置 |
|------|---------|---------|
| 时间线 | 过滤拉黑用户的 Posts | `GetTimelineWithStatusUseCase` |
| 回复列表 | 过滤拉黑用户的回复 | `GetRepliesWithStatusUseCase` |
| 用户 Posts | 过滤拉黑用户的 Posts | `GetUserPostsWithStatusUseCase` |
| 用户回复 | 过滤拉黑用户的回复 | `GetUserRepliesWithStatusUseCase` |
| 用户点赞 | 过滤拉黑用户的 Posts | `GetUserLikesWithStatusUseCase` |
| 用户收藏 | 过滤拉黑用户的 Posts | `GetUserBookmarksWithStatusUseCase` |
| Post 详情 | 返回 PostNotFound（隐藏） | `GetPostDetailWithStatusUseCase` |

### 用户资料

| 场景 | 影响 | 实现位置 |
|------|------|---------|
| 查看资料 | 返回 `isBlockedByCurrentUser` 字段 | `GetUserProfileUseCase` |

### 过滤策略说明

**UseCase 层过滤**（而非数据库层）的原因：
1. **关注点分离**：Repository 层只负责数据存取，过滤逻辑属于业务规则
2. **性能可接受**：拉黑用户数通常很少，内存过滤开销可忽略
3. **复用性**：排除集合 `getBlockedRelationUserIds()` 只查询一次，所有过滤都是 O(1) Set 查找
4. **可维护性**：所有拉黑过滤逻辑集中在 UseCase 层，不散落在各 SQL 查询中

---

## Error Handling 策略

### UserError 扩展

```kotlin
sealed interface UserError {
    // ... 原有错误 ...

    // 拉黑相关错误
    data object CannotBlockSelf : UserError         // 400 Bad Request
    data object AlreadyBlocked : UserError          // 409 Conflict
    data object NotBlocked : UserError              // 400 Bad Request
    data class BlockTargetNotFound(userId) : UserError  // 404 Not Found
    data class UserBlocked(userId) : UserError      // 403 Forbidden
}
```

### MessageError 扩展

```kotlin
sealed interface MessageError {
    // ... 原有错误 ...

    data class UserBlocked(userId) : MessageError   // 403 Forbidden
}
```

### Transport 层映射

| 错误 | HTTP 状态码 | code |
|------|------------|------|
| `UserError.CannotBlockSelf` | 400 | `CANNOT_BLOCK_SELF` |
| `UserError.AlreadyBlocked` | 409 | `ALREADY_BLOCKED` |
| `UserError.NotBlocked` | 400 | `NOT_BLOCKED` |
| `UserError.BlockTargetNotFound` | 404 | `BLOCK_TARGET_NOT_FOUND` |
| `UserError.UserBlocked` | 403 | `USER_BLOCKED` |
| `MessageError.UserBlocked` | 403 | `USER_BLOCKED` |

---

## 数据流示例

### 拉黑用户的完整流程

```
Client Request
    POST /v1/users/{userId}/block
    Authorization: Bearer <token>
    ↓
UserRoutes.kt (Transport Layer)
    - JWT 验证 → UserPrincipal
    - 解析 blockerId（当前用户）和 blockedId（路径参数）
    ↓
BlockUserUseCase (Application Service)
    - 验证：blockerId != blockedId
    - 调用 userRepository.block(blockerId, blockedId)
    - 成功后自动解除双向关注：
      - userRepository.unfollow(blockerId, blockedId)  // 忽略 NotFollowing
      - userRepository.unfollow(blockedId, blockerId)  // 忽略 NotFollowing
    ↓
ExposedUserRepository (Infrastructure)
    - 检查目标用户是否存在
    - INSERT INTO blocks (blocker_id, blocked_id, created_at)
    - 捕获 unique constraint violation → AlreadyBlocked
    - DELETE FROM follows WHERE ...（双向取消关注）
    ↓
UserRoutes.kt
    - 成功: 200 { "message": "拉黑成功" }
    - 失败: 映射 UserError → HTTP Status
    ↓
Client Response
```

### 时间线中的拉黑过滤流程

```
Client Request
    GET /v1/posts/timeline?limit=20
    Authorization: Bearer <token>
    ↓
GetTimelineWithStatusUseCase
    1. 查询时间线 Posts（postRepository.findTimeline）
    2. 查询拉黑排除集合（userRepository.getBlockedRelationUserIds）
    3. 过滤：posts.filter { it.post.authorId !in blockedUserIds }
    4. 批量查询交互状态（batchCheckLiked, batchCheckBookmarked）
    5. 返回过滤后的结果
    ↓
Client Response
    - 不包含拉黑关系用户的任何 Post
```

---

## DI 配置

```kotlin
// DomainModule.kt
single { BlockUserUseCase(get()) }    // 注入 UserRepository
single { UnblockUserUseCase(get()) }  // 注入 UserRepository
```

已有 UseCase 的 DI 变更（新增 `UserRepository` 依赖）：

| UseCase | 变更 |
|---------|------|
| `GetTimelineWithStatusUseCase` | `(get())` → `(get(), get())` |
| `GetRepliesWithStatusUseCase` | `(get())` → `(get(), get())` |
| `GetPostDetailWithStatusUseCase` | `(get())` → `(get(), get())` |

以下 UseCase 已有 `UserRepository`，无需变更 DI：
- `GetUserPostsWithStatusUseCase`
- `GetUserRepliesWithStatusUseCase`
- `GetUserLikesWithStatusUseCase`
- `GetUserBookmarksWithStatusUseCase`
- `FollowUserUseCase`
- `SendMessageUseCase`

---

## 文件变更清单

### 新增文件

| 文件 | 层 | 说明 |
|------|---|------|
| `domain/model/Block.kt` | Domain | 拉黑关系实体 |
| `data/db/schema/BlocksTable.kt` | Infrastructure | 数据库表定义 |
| `domain/usecase/BlockUserUseCase.kt` | Domain | 拉黑用例 |
| `domain/usecase/UnblockUserUseCase.kt` | Domain | 取消拉黑用例 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `domain/failure/UserErrors.kt` | 新增拉黑相关错误类型 |
| `domain/failure/MessageErrors.kt` | 新增 `UserBlocked` 错误 |
| `domain/repository/UserRepository.kt` | 新增 block/unblock/isBlocked/getBlockedRelationUserIds |
| `data/repository/ExposedUserRepository.kt` | 实现 block 相关方法 |
| `data/db/DatabaseFactory.kt` | 注册 BlocksTable |
| `features/user/UserRoutes.kt` | 新增 block/unblock 路由 |
| `features/user/UserMappers.kt` | 新增 block 错误映射 + profile blocked 状态 |
| `features/user/UserSchema.kt` | UserProfileResponse 新增 isBlockedByCurrentUser |
| `features/messaging/MessagingMappers.kt` | 新增 UserBlocked 错误映射 |
| `core/di/DomainModule.kt` | 注册 BlockUserUseCase / UnblockUserUseCase，更新依赖 |
| `plugins/Routing.kt` | 注入并传递 block use cases |
| `domain/usecase/FollowUserUseCase.kt` | 关注前检查拉黑 |
| `domain/usecase/SendMessageUseCase.kt` | 发送前检查拉黑 |
| `domain/usecase/GetUserProfileUseCase.kt` | ProfileView 新增 isBlockedByCurrentUser |
| `domain/usecase/GetTimelineWithStatusUseCase.kt` | 新增 userRepository + 过滤 |
| `domain/usecase/GetRepliesWithStatusUseCase.kt` | 新增 userRepository + 过滤 |
| `domain/usecase/GetPostDetailWithStatusUseCase.kt` | 新增 userRepository + 拉黑隐藏 |
| `domain/usecase/GetUserPostsWithStatusUseCase.kt` | 新增过滤 |
| `domain/usecase/GetUserRepliesWithStatusUseCase.kt` | 新增过滤 |
| `domain/usecase/GetUserLikesWithStatusUseCase.kt` | 新增过滤 |
| `domain/usecase/GetUserBookmarksWithStatusUseCase.kt` | 新增过滤 |
