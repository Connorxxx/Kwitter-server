# 拉黑 & 删除 Post 客户端接入指南

**更新时间**: 2026-02-13
**适用**: Android (Kotlin) / iOS (Swift) / Web (TypeScript)
**前置阅读**: [auth-client-integration-guide.md](./auth-client-integration-guide.md)

---

## 1. 快速接入清单

### 删除 Post
- [ ] 实现删除 Post（`DELETE /v1/posts/{postId}`），仅允许作者本人
- [ ] UI 上仅对作者显示删除按钮
- [ ] 删除成功后从本地列表移除

### 拉黑用户
- [ ] 实现拉黑用户（`POST /v1/users/{userId}/block`）
- [ ] 实现取消拉黑（`DELETE /v1/users/{userId}/block`）
- [ ] 用户资料页显示拉黑状态（`isBlockedByCurrentUser` 字段）
- [ ] 拉黑后刷新本地时间线/关注列表（过滤已在服务端完成）
- [ ] 处理拉黑导致的关注/私信失败（`USER_BLOCKED` 错误码）

---

## 2. API 端点总览

### 删除 Post

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| `DELETE` | `/v1/posts/{postId}` | 删除 Post | 必须 |

### 拉黑用户

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| `POST` | `/v1/users/{userId}/block` | 拉黑用户 | 必须 |
| `DELETE` | `/v1/users/{userId}/block` | 取消拉黑 | 必须 |

所有端点要求 JWT 认证，未携带或过期返回 `401`。

---

## 3. 删除 Post

### 请求

```
DELETE /v1/posts/{postId}
Authorization: Bearer <token>
```

**业务规则**：
- 只有 Post 的作者才能删除
- 如果是回复，删除后自动递减父 Post 的 `replyCount`
- 同时删除关联的媒体附件记录

### 成功响应 `200 OK`

```json
{
  "message": "Post 已删除"
}
```

### 错误响应

| HTTP 状态 | code | 场景 |
|-----------|------|------|
| `400` | `MISSING_PARAM` | 缺少 postId 参数 |
| `401` | `UNAUTHORIZED` | 未携带或无效 JWT |
| `403` | `UNAUTHORIZED` | 尝试删除他人的 Post |
| `404` | `POST_NOT_FOUND` | Post 不存在 |

### 示例代码

```kotlin
// Android - Ktor Client
suspend fun deletePost(postId: String) {
    val response = httpClient.delete("$BASE_URL/v1/posts/$postId")
    if (response.status != HttpStatusCode.OK) {
        val error = response.body<ApiErrorResponse>()
        throw ApiException(error.code, error.message)
    }
}
```

```typescript
// Web - TypeScript
async function deletePost(postId: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/v1/posts/${postId}`, {
    method: 'DELETE',
    headers: { 'Authorization': `Bearer ${token}` }
  });

  if (!res.ok) {
    const error = await res.json();
    throw new ApiError(error.code, error.message);
  }
}
```

### 客户端处理

```kotlin
// Android - 删除确认对话框 + 本地列表更新
fun onDeletePostClicked(postId: String) {
    showConfirmDialog("确定要删除这条推文吗？") {
        viewModelScope.launch {
            try {
                api.deletePost(postId)
                // 从本地列表移除
                _posts.value = _posts.value.filter { it.id != postId }
            } catch (e: ApiException) {
                when (e.code) {
                    "UNAUTHORIZED" -> showError("无权删除此推文")
                    "POST_NOT_FOUND" -> showError("推文不存在")
                    else -> showError("删除失败，请重试")
                }
            }
        }
    }
}
```

---

## 4. 拉黑用户

### 4.1 拉黑

#### 请求

```
POST /v1/users/{userId}/block
Authorization: Bearer <token>
```

无需请求体。

#### 成功响应 `200 OK`

```json
{
  "message": "拉黑成功"
}
```

**服务端自动处理的副作用**：
- 自动解除双向关注关系（你关注对方、对方关注你）
- 后续 API 调用中，对方的内容将被过滤（时间线、回复列表等）

#### 错误响应

| HTTP 状态 | code | 场景 |
|-----------|------|------|
| `400` | `CANNOT_BLOCK_SELF` | 尝试拉黑自己 |
| `404` | `BLOCK_TARGET_NOT_FOUND` | 目标用户不存在 |
| `409` | `ALREADY_BLOCKED` | 已经拉黑该用户 |

### 4.2 取消拉黑

#### 请求

```
DELETE /v1/users/{userId}/block
Authorization: Bearer <token>
```

无需请求体。

#### 成功响应 `200 OK`

```json
{
  "message": "取消拉黑成功"
}
```

**注意**：取消拉黑**不会**自动恢复关注关系，需要用户手动重新关注。

#### 错误响应

| HTTP 状态 | code | 场景 |
|-----------|------|------|
| `400` | `CANNOT_BLOCK_SELF` | 参数错误 |
| `400` | `NOT_BLOCKED` | 未拉黑该用户 |

### 示例代码

```kotlin
// Android - Ktor Client
suspend fun blockUser(userId: String) {
    val response = httpClient.post("$BASE_URL/v1/users/$userId/block")
    if (response.status != HttpStatusCode.OK) {
        val error = response.body<ApiErrorResponse>()
        throw ApiException(error.code, error.message)
    }
}

suspend fun unblockUser(userId: String) {
    val response = httpClient.delete("$BASE_URL/v1/users/$userId/block")
    if (response.status != HttpStatusCode.OK) {
        val error = response.body<ApiErrorResponse>()
        throw ApiException(error.code, error.message)
    }
}
```

```typescript
// Web - TypeScript
async function blockUser(userId: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/v1/users/${userId}/block`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` }
  });
  if (!res.ok) {
    const error = await res.json();
    throw new ApiError(error.code, error.message);
  }
}

async function unblockUser(userId: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/v1/users/${userId}/block`, {
    method: 'DELETE',
    headers: { 'Authorization': `Bearer ${token}` }
  });
  if (!res.ok) {
    const error = await res.json();
    throw new ApiError(error.code, error.message);
  }
}
```

---

## 5. 用户资料中的拉黑状态

### 响应变更

`GET /v1/users/{userId}` 和 `GET /v1/users/username/{username}` 的响应新增 `isBlockedByCurrentUser` 字段：

```json
{
  "user": {
    "id": "user-uuid",
    "username": "alice",
    "displayName": "Alice",
    "bio": "Hello world",
    "avatarUrl": "https://example.com/avatar.jpg",
    "createdAt": 1707600000000
  },
  "stats": {
    "followingCount": 42,
    "followersCount": 100,
    "postsCount": 256
  },
  "isFollowedByCurrentUser": false,
  "isBlockedByCurrentUser": true
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `isBlockedByCurrentUser` | `boolean?` | `true` = 当前用户已拉黑该用户，`false` = 未拉黑，`null` = 当前用户未认证或查看自己 |

### 客户端处理

```kotlin
// Android - 用户资料页
@Composable
fun UserProfileScreen(profile: UserProfileResponse) {
    // 根据拉黑状态显示不同按钮
    when {
        profile.isBlockedByCurrentUser == true -> {
            Button(onClick = { unblockUser(profile.user.id) }) {
                Text("取消拉黑")
            }
        }
        else -> {
            // 显示关注/已关注按钮
            FollowButton(profile)

            // 更多菜单中的拉黑选项
            DropdownMenuItem(
                text = { Text("拉黑用户") },
                onClick = { blockUser(profile.user.id) }
            )
        }
    }
}
```

```typescript
// Web - TypeScript
function UserProfile({ profile }: { profile: UserProfileResponse }) {
  if (profile.isBlockedByCurrentUser) {
    return <button onClick={() => unblockUser(profile.user.id)}>取消拉黑</button>;
  }

  return (
    <>
      <FollowButton profile={profile} />
      <DropdownMenu>
        <MenuItem onClick={() => blockUser(profile.user.id)}>拉黑用户</MenuItem>
      </DropdownMenu>
    </>
  );
}
```

---

## 6. 拉黑对其他功能的影响

拉黑后，以下操作会被服务端自动阻止或过滤，客户端需处理对应的错误码：

### 6.1 关注被阻止

尝试关注拉黑关系中的用户会返回 `403`：

```json
{
  "code": "USER_BLOCKED",
  "message": "用户已被拉黑: user-uuid"
}
```

客户端处理：
```kotlin
// 关注失败时检查是否是拉黑导致
try {
    api.followUser(userId)
} catch (e: ApiException) {
    when (e.code) {
        "USER_BLOCKED" -> showError("无法关注：存在拉黑关系")
        else -> showError(e.message)
    }
}
```

### 6.2 私信被阻止

尝试给拉黑关系中的用户发消息会返回 `403`：

```json
{
  "code": "USER_BLOCKED",
  "message": "无法发送消息，用户已被拉黑: user-uuid"
}
```

### 6.3 内容自动过滤

以下 API 的返回结果会**自动过滤**拉黑关系用户的内容，客户端无需额外处理：

| API | 影响 |
|-----|------|
| `GET /v1/posts/timeline` | 时间线中不包含拉黑用户的 Posts |
| `GET /v1/posts/{postId}/replies` | 回复列表中不包含拉黑用户的回复 |
| `GET /v1/posts/{postId}` | 如果作者被拉黑，返回 `404 POST_NOT_FOUND` |
| `GET /v1/users/{userId}/posts` | 过滤拉黑用户的 Posts |
| `GET /v1/users/{userId}/replies` | 过滤拉黑用户的回复 |
| `GET /v1/users/{userId}/likes` | 过滤拉黑用户的 Posts |
| `GET /v1/posts/bookmarks` | 过滤拉黑用户的 Posts |

**客户端建议**：拉黑/取消拉黑操作后，刷新当前页面的数据以反映最新的过滤状态。

---

## 7. TypeScript 类型定义

```typescript
// 用户资料响应（更新）
interface UserProfileResponse {
  user: UserDto;
  stats: UserStatsDto;
  isFollowedByCurrentUser: boolean | null;
  isBlockedByCurrentUser: boolean | null;  // 新增
}

// 错误响应
interface ApiErrorResponse {
  code: string;
  message: string;
  timestamp?: number;
}
```

---

## 8. Kotlin 数据类（Android / KMP）

```kotlin
@Serializable
data class UserProfileResponse(
    val user: UserDto,
    val stats: UserStatsDto,
    val isFollowedByCurrentUser: Boolean? = null,
    val isBlockedByCurrentUser: Boolean? = null  // 新增
)
```

---

## 9. 完整交互流程

### 9.1 拉黑用户

```
用户 A 拉黑用户 B

A: POST /v1/users/B-id/block

Server:
  1. 验证 A != B
  2. INSERT INTO blocks (A, B)
  3. DELETE FROM follows WHERE (A→B) OR (B→A)    ← 自动

A ← 200 { "message": "拉黑成功" }

后续效果：
  - A 的时间线不再显示 B 的 Posts
  - B 的时间线不再显示 A 的 Posts
  - A 尝试关注 B → 403 USER_BLOCKED
  - B 尝试关注 A → 403 USER_BLOCKED
  - A 给 B 发私信 → 403 USER_BLOCKED
  - B 给 A 发私信 → 403 USER_BLOCKED
  - A 查看 B 的资料 → isBlockedByCurrentUser: true
```

### 9.2 取消拉黑

```
用户 A 取消拉黑用户 B

A: DELETE /v1/users/B-id/block

Server:
  1. DELETE FROM blocks WHERE (A, B)

A ← 200 { "message": "取消拉黑成功" }

后续效果：
  - 恢复正常：A 和 B 可以互相看到内容
  - 关注关系不自动恢复（需手动重新关注）
```

### 9.3 删除 Post

```
用户 A 删除自己的回复（parentId = post-1）

A: DELETE /v1/posts/reply-1

Server:
  1. 验证 reply-1 的 authorId == A
  2. 读取 reply-1 的 parentId = post-1
  3. DELETE FROM media WHERE post_id = reply-1
  4. DELETE FROM posts WHERE id = reply-1
  5. UPDATE posts SET reply_count = reply_count - 1 WHERE id = post-1

A ← 200 { "message": "Post 已删除" }
```

---

## 10. 错误码汇总

### 删除 Post

| HTTP 状态 | code | message | 场景 |
|-----------|------|---------|------|
| `400` | `MISSING_PARAM` | 缺少 postId 参数 | URL 参数缺失 |
| `401` | `UNAUTHORIZED` | 未授权访问 | 未携带或无效 JWT |
| `403` | `UNAUTHORIZED` | 用户无权执行操作: delete | 非作者尝试删除 |
| `404` | `POST_NOT_FOUND` | Post 不存在 | postId 无效 |

### 拉黑用户

| HTTP 状态 | code | message | 场景 |
|-----------|------|---------|------|
| `400` | `CANNOT_BLOCK_SELF` | 不能拉黑自己 | userId 等于自己 |
| `400` | `NOT_BLOCKED` | 未拉黑该用户 | 取消拉黑时未拉黑 |
| `401` | `UNAUTHORIZED` | 未授权访问 | 未携带或无效 JWT |
| `404` | `BLOCK_TARGET_NOT_FOUND` | 目标用户不存在 | userId 无效 |
| `409` | `ALREADY_BLOCKED` | 已经拉黑该用户 | 重复拉黑 |

### 拉黑导致的操作失败

| HTTP 状态 | code | message | 触发场景 |
|-----------|------|---------|---------|
| `403` | `USER_BLOCKED` | 用户已被拉黑 | 关注拉黑关系中的用户 |
| `403` | `USER_BLOCKED` | 无法发送消息，用户已被拉黑 | 给拉黑关系中的用户发私信 |

错误响应格式统一为：

```json
{
  "code": "ERROR_CODE",
  "message": "人类可读的错误描述",
  "timestamp": 1707600000000
}
```

---

## 11. 测试检查清单

### 删除 Post

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 1 | A 删除自己的 Post | 200，Post 已删除 |
| 2 | A 删除 B 的 Post | 403 `UNAUTHORIZED` |
| 3 | A 删除不存在的 Post | 404 `POST_NOT_FOUND` |
| 4 | 创建回复后删除回复 | 200，父 Post 的 replyCount 递减 |
| 5 | 未认证删除 Post | 401 |

### 拉黑用户

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 6 | A 拉黑 B | 200，A 和 B 自动取消关注 |
| 7 | A 再次拉黑 B | 409 `ALREADY_BLOCKED` |
| 8 | A 拉黑自己 | 400 `CANNOT_BLOCK_SELF` |
| 9 | A 拉黑不存在的用户 | 404 `BLOCK_TARGET_NOT_FOUND` |
| 10 | A 取消拉黑 B | 200 |
| 11 | A 取消拉黑（未拉黑的）C | 400 `NOT_BLOCKED` |

### 拉黑后行为

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 12 | A 拉黑 B 后，A 关注 B | 403 `USER_BLOCKED` |
| 13 | A 拉黑 B 后，B 关注 A | 403 `USER_BLOCKED` |
| 14 | A 拉黑 B 后，A 给 B 发私信 | 403 `USER_BLOCKED` |
| 15 | A 拉黑 B 后，B 给 A 发私信 | 403 `USER_BLOCKED` |
| 16 | A 拉黑 B 后，A 查时间线 | B 的 Posts 不出现 |
| 17 | A 拉黑 B 后，A 查 B 的 Post 详情 | 404 `POST_NOT_FOUND` |
| 18 | A 拉黑 B 后，A 查 B 的资料 | `isBlockedByCurrentUser: true` |
| 19 | A 取消拉黑 B 后，A 关注 B | 200，关注成功 |
| 20 | A 取消拉黑 B 后，A 给 B 发私信 | 201，发送成功 |
