# 实时推送通知功能设计文档

## 架构概览

遵循 Hexagonal Architecture (Ports & Adapters) 和 DDD 原则，设计实时推送功能：

```
┌─────────────────────────────────────────────────────────────┐
│                        Domain Layer                         │
│  (纯 Kotlin，无框架依赖，业务规则的唯一真相来源)               │
├─────────────────────────────────────────────────────────────┤
│  Models:                                                    │
│    - NotificationEvent (密封接口)                           │
│      - NewPostCreated                                       │
│      - PostLiked                                            │
│      - PostUnliked                                          │
│      - PostCommented                                        │
│    - NotificationTarget (订阅目标)                          │
│                                                             │
│  Repository (Port/Interface):                              │
│    - NotificationRepository                                │
│      - broadcastNewPost()                                  │
│      - notifyPostLiked()                                   │
│      - notifyPostUnliked()                                 │
│      - notifyNewMessage()                                  │
│      - notifyMessagesRead()                                │
│      - notifyMessageRecalled()                             │
│      - notifyTypingIndicator()                             │
│                                                             │
│  Use Cases:                                                │
│    - BroadcastPostCreatedUseCase                           │
│    - BroadcastPostLikedUseCase                             │
│    - BroadcastPostUnlikedUseCase                           │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   Infrastructure Layer                      │
│              (SSE、内存订阅管理)                              │
├─────────────────────────────────────────────────────────────┤
│  - SseConnectionManager                                    │
│    - 管理客户端连接 (Channel<ServerSentEvent>)               │
│    - 维护用户连接 (userId -> Set<connectionId>)             │
│    - 维护页面订阅 (postId -> Set<userId>)                   │
│  - InMemoryNotificationRepository                          │
│    - 实现通知推送逻辑                                         │
│    - 通过 SseConnectionManager 推送类型化 SSE 事件           │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      Transport Layer                        │
│               (SSE + REST Endpoints)                        │
├─────────────────────────────────────────────────────────────┤
│  SSE:                                                      │
│  GET  /v1/notifications/stream     → 事件流（需要JWT认证）  │
│                                                             │
│  REST（客户端命令）:                                         │
│  POST   /v1/notifications/posts/{postId}/subscribe          │
│  DELETE /v1/notifications/posts/{postId}/subscribe          │
│  PUT    /v1/messaging/conversations/{convId}/typing         │
└─────────────────────────────────────────────────────────────┘
```

---

## 为什么选择 SSE 而非 WebSocket

1. **Server→Client 推送是主要模式** — 仅有 4 个小型客户端→服务端命令
2. **SSE 原生重连** — 通过 `Last-Event-ID` 消除自定义重连逻辑
3. **SSE 是 HTTP 原生** — 通过所有代理/CDN，标准 `Authorization` 头认证
4. **更简单的服务端模型** — 无帧协议，无 ping/pong 协商
5. **客户端命令变为 REST** — 正确的 HTTP 语义（状态码、限流、错误处理）

---

## SSE 事件格式

标准 SSE 协议，使用类型化事件：

```
event: new_post
id: 42
data: {"postId":123,"authorId":456,"content":"...","createdAt":1709000000}

event: presence_snapshot
id: 43
data: {"users":[{"userId":789,"isOnline":true,"timestamp":1709000000}]}

:heartbeat
```

- `event` 字段 → 消息类型分发（替代 `{"type":"..."}` JSON 包装）
- `id` 字段 → 单调递增的服务端计数器（支持未来 `Last-Event-ID` 重放）
- `data` 字段 → JSON 负载（直接数据，无包装层）
- 注释行 (`:heartbeat`) → 每 30 秒心跳保活

---

## 功能需求

### 1. 新 Post 推送
- **触发条件**: 用户创建新的顶层 Post（非回复）
- **推送对象**: 所有在线用户（全局广播）
- **推送内容**: Post 摘要 + 作者信息

### 2. Post 点赞推送
- **触发条件**: 用户点赞某个 Post
- **推送对象**: 当前正在查看该 Post 详情页的所有用户
- **推送内容**: 更新的点赞数 + 点赞用户信息

### 2.1 Post 取消点赞推送
- **触发条件**: 用户取消点赞某个 Post
- **推送对象**: 当前正在查看该 Post 详情页的所有用户
- **推送内容**: 更新的点赞数 + `isLiked=false`

### 3. 订阅管理
- **页面订阅**: 客户端打开 Post 详情页时，调用 REST 端点订阅
- **页面取消订阅**: 客户端离开页面时，调用 REST 端点取消订阅
- **自动清理**: SSE 连接断开时自动清理该用户的所有订阅

### 4. 私信推送
- 新消息、已读回执、消息撤回、打字状态、在线状态

---

## Domain Models 设计

### 1. NotificationEvent (密封接口)

```kotlin
sealed interface NotificationEvent {
    data class NewPostCreated(
        val postId: Long, val authorId: Long,
        val authorDisplayName: String, val authorUsername: String,
        val content: String, val createdAt: Long
    ) : NotificationEvent

    data class PostLiked(
        val postId: Long, val likedByUserId: Long,
        val likedByDisplayName: String, val likedByUsername: String,
        val newLikeCount: Int, val timestamp: Long
    ) : NotificationEvent

    data class PostUnliked(
        val postId: Long, val unlikedByUserId: Long,
        val newLikeCount: Int, val isLiked: Boolean = false,
        val timestamp: Long
    ) : NotificationEvent

    data class PostCommented(...) : NotificationEvent
    data class NewMessageReceived(...) : NotificationEvent
    data class MessagesRead(...) : NotificationEvent
    data class MessageRecalled(...) : NotificationEvent
    data class TypingIndicator(...) : NotificationEvent
}
```

### 2. NotificationTarget

```kotlin
sealed interface NotificationTarget {
    data object Everyone : NotificationTarget
    data class SpecificUser(val userId: UserId) : NotificationTarget
    data class PostSubscribers(val postId: PostId) : NotificationTarget
}
```

---

## Repository 接口设计

### NotificationRepository

```kotlin
interface NotificationRepository {
    suspend fun broadcastNewPost(event: NotificationEvent.NewPostCreated)
    suspend fun notifyPostLiked(event: NotificationEvent.PostLiked)
    suspend fun notifyPostUnliked(event: NotificationEvent.PostUnliked)
    suspend fun notifyPostCommented(event: NotificationEvent.PostCommented)
    suspend fun notifyNewMessage(recipientId: UserId, event: NotificationEvent.NewMessageReceived)
    suspend fun notifyMessagesRead(recipientId: UserId, event: NotificationEvent.MessagesRead)
    suspend fun notifyMessageRecalled(recipientId: UserId, event: NotificationEvent.MessageRecalled)
    suspend fun notifyTypingIndicator(recipientId: UserId, event: NotificationEvent.TypingIndicator)
}
```

---

## Infrastructure 实现设计

### 1. SseConnectionManager

```kotlin
class SseConnectionManager {
    data class SseConnection(
        val id: String,
        val userId: UserId,
        val channel: Channel<ServerSentEvent>
    )

    // 连接映射
    private val connections: ConcurrentHashMap<String, SseConnection>
    private val userConnections: ConcurrentHashMap<UserId, MutableSet<String>>

    // Post 订阅映射（用户级而非会话级 — 支持多设备同步）
    private val postSubscriptions: ConcurrentHashMap<PostId, MutableSet<UserId>>

    // 单调递增事件 ID
    private val eventIdCounter: AtomicLong

    fun registerConnection(userId: UserId): SseConnection
    fun removeConnection(connectionId: String)
    fun subscribeToPost(userId: UserId, postId: PostId)
    fun unsubscribeFromPost(userId: UserId, postId: PostId)
    suspend fun sendToUser(userId: UserId, event: String, data: String)
    suspend fun sendToUsers(userIds: Collection<UserId>, event: String, data: String)
    suspend fun broadcastToAll(event: String, data: String)
    suspend fun sendToPostSubscribers(postId: PostId, event: String, data: String)
    fun isUserOnline(userId: UserId): Boolean
    fun getOnlineStatus(userIds: Collection<UserId>): Map<UserId, Boolean>
}
```

**关键设计决策**:
- **Post 订阅为用户级** (`PostId → Set<UserId>`)，而非会话级 — 一个设备订阅，所有设备收到事件
- **Channel-based 推送** — 天然背压（有界 Channel 容量），Channel 关闭即连接清理
- **惰性清理** — 发送失败时自动移除过期连接

### 2. InMemoryNotificationRepository

```kotlin
class InMemoryNotificationRepository(
    private val connectionManager: SseConnectionManager
) : NotificationRepository {
    // 每个方法：将领域事件 JSON 序列化 → 通过 connectionManager 发送类型化 SSE 事件
    // event 参数 = SSE event 字段（如 "new_post"、"post_liked"）
    // data 参数 = JSON 负载（直接数据，无包装层）
}
```

---

## Transport Layer 设计

### SSE Stream Endpoint

```kotlin
fun Route.notificationSse(connectionManager, notificationRepository, messageRepository) {
    authenticate("auth-jwt") {
        sse("/v1/notifications/stream") {
            val userId = authenticate()
            val connection = connectionManager.registerConnection(userId)

            // 1. 发送 connected 确认
            send(ServerSentEvent(data=..., event="connected", id=...))

            // 2. 发送 presence_snapshot（保证必发）
            send(ServerSentEvent(data=..., event="presence_snapshot", id=...))

            // 3. 首次上线广播 user_presence_changed 给对话对端

            // 4. 心跳（每 30 秒发送 :heartbeat 注释）
            val heartbeatJob = launch { heartbeatLoop() }

            // 5. 从 Channel 读取事件并转发到 SSE 流
            try {
                for (event in connection.channel) {
                    send(event)
                }
            } finally {
                heartbeatJob.cancel()
                connectionManager.removeConnection(connection.id)
                // 最后一个会话断开 → 广播下线
            }
        }
    }
}
```

### REST Command Endpoints

```kotlin
// Post 订阅
fun Route.notificationCommandRoutes(connectionManager) {
    authenticate("auth-jwt") {
        post("/v1/notifications/posts/{postId}/subscribe") {
            connectionManager.subscribeToPost(userId, postId)
            call.respond(HttpStatusCode.OK)
        }

        delete("/v1/notifications/posts/{postId}/subscribe") {
            connectionManager.unsubscribeFromPost(userId, postId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// 打字状态（在 MessagingRoutes 中）
put("/v1/messaging/conversations/{conversationId}/typing") {
    val request = call.receive<TypingRequest>()  // { "isTyping": true/false }
    // 查找对话 → 找到对方 → 通过 SSE 推送 typing_indicator
    call.respond(HttpStatusCode.NoContent)
}
```

---

## 集成到现有 Use Cases

### CreatePostUseCase

```kotlin
// Post 创建成功后，异步广播通知
result.onRight { createdPost ->
    if (createdPost.parentId == null) {
        appScope.launch {
            broadcastPostCreatedUseCase.execute(...)
        }
    }
}
```

### LikePostUseCase

```kotlin
// 点赞成功后，异步推送给 Post 订阅者
result.onRight { stats ->
    appScope.launch {
        broadcastPostLikedUseCase.execute(...)
    }
}
```

### UnlikePostUseCase

```kotlin
// 取消点赞成功后，异步推送给 Post 订阅者
result.onRight { stats ->
    appScope.launch {
        broadcastPostUnlikedUseCase.execute(...)
    }
}
```

---

## 性能和扩展性考虑

### 当前实现（单机版）

- **连接管理**: 内存中维护 `ConcurrentHashMap<UserId, Set<connectionId>>`
- **订阅管理**: 内存中维护 `ConcurrentHashMap<PostId, Set<UserId>>`
- **消息推送**: 遍历用户连接的 Channel 发送
- **背压**: Channel 有界缓冲区天然支持
- **适用规模**: < 10,000 并发连接

### 未来扩展（分布式）

1. **Redis Pub/Sub**: 多服务器实例订阅同一 channel，跨服务器广播
2. **Redis 连接管理**: `userId -> serverInstanceId` 消息路由
3. **负载均衡**: SSE 是标准 HTTP — 任何 HTTP 负载均衡器/CDN 均可使用

---

## 安全考虑

### 已实现

- SSE 连接必须携带有效 JWT Token（标准 `Authorization` 头）
- 使用 Ktor 的 `authenticate("auth-jwt")` 保护所有端点
- 推送消息不包含敏感信息

### 未来增强

- REST 命令端点速率限制
- 订阅数量限制
- 私密 Post 权限检查

---

## 测试策略

### 1. 单元测试

- `SseConnectionManager` 的连接和订阅管理逻辑
- `InMemoryNotificationRepository` 的推送逻辑

### 2. 集成测试

- SSE 连接和事件接收
- 多客户端订阅和广播

### 3. 手动验证

```bash
# 连接 SSE 流
curl -N -H "Authorization: Bearer <token>" \
  http://localhost:8080/v1/notifications/stream

# 应收到：
# event: connected
# id: 1
# data: {"userId":123}
#
# event: presence_snapshot
# id: 2
# data: {"users":[...]}
#
# :heartbeat  (每 30 秒)
```

```bash
# 订阅 Post
curl -X POST -H "Authorization: Bearer <token>" \
  http://localhost:8080/v1/notifications/posts/123/subscribe

# 取消订阅
curl -X DELETE -H "Authorization: Bearer <token>" \
  http://localhost:8080/v1/notifications/posts/123/subscribe
```

---

## 实现清单

### Domain Layer
- [x] NotificationEvent 密封接口
- [x] NotificationTarget 定义
- [x] NotificationRepository 接口

### Infrastructure Layer
- [x] SseConnectionManager 实现
- [x] SseSessionNotifier 实现
- [x] InMemoryNotificationRepository 实现

### Use Cases
- [x] BroadcastPostCreatedUseCase
- [x] BroadcastPostLikedUseCase
- [x] BroadcastPostUnlikedUseCase
- [x] 修改 CreatePostUseCase 集成通知
- [x] 修改 LikePostUseCase 集成通知
- [x] 修改 UnlikePostUseCase 集成通知

### Transport Layer
- [x] SSE stream endpoint (`/v1/notifications/stream`)
- [x] Post subscribe/unsubscribe REST endpoints
- [x] Typing REST endpoint
- [x] TypingRequest DTO

### Configuration
- [x] 添加 `ktor-server-sse` 依赖
- [x] SSE 插件安装 (`plugins/SSE.kt`)
- [x] Koin DI 配置
- [x] Routing 配置

---

## 设计完成

这个设计完全遵循现有的 Hexagonal Architecture，核心业务逻辑在 Domain 层，SSE 实现在 Infrastructure 层，协议转换在 Transport 层。推送功能不会阻塞主业务流程，失败时优雅降级。相比之前的 WebSocket 方案，SSE + REST 更简单、更符合 HTTP 语义。
