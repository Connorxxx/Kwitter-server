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
│      - PostCommented                                        │
│    - NotificationTarget (订阅目标)                          │
│    - WebSocketSession (会话抽象)                            │
│                                                             │
│  Repository (Port/Interface):                              │
│    - NotificationRepository                                │
│      - broadcastNewPost()                                  │
│      - notifyPostLiked()                                   │
│      - subscribeToPost()                                   │
│      - unsubscribeFromPost()                               │
│                                                             │
│  Use Cases:                                                │
│    - BroadcastPostCreatedUseCase                           │
│    - BroadcastPostLikedUseCase                             │
│    - ManageNotificationSubscriptionsUseCase                │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   Infrastructure Layer                      │
│              (WebSocket、内存订阅管理)                        │
├─────────────────────────────────────────────────────────────┤
│  - WebSocketConnectionManager                              │
│    - 管理客户端连接                                          │
│    - 维护订阅关系 (userId -> sessions)                      │
│    - 维护页面订阅 (postId -> sessions)                      │
│  - InMemoryNotificationRepository                          │
│    - 实现通知推送逻辑                                         │
│    - 处理订阅/取消订阅                                       │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      Transport Layer                        │
│                   (WebSocket Endpoint)                      │
├─────────────────────────────────────────────────────────────┤
│  - WebSocket /v1/notifications/ws                          │
│    - 接受连接（需要JWT认证）                                 │
│    - 处理客户端订阅消息                                      │
│    - 发送实时通知                                            │
└─────────────────────────────────────────────────────────────┘
```

---

## 功能需求

### 1. 新 Post 推送
- **触发条件**: 用户创建新的顶层 Post（非回复）
- **推送对象**: 所有在线用户（全局广播）
- **推送内容**: Post 摘要 + 作者信息

### 2. Post 点赞推送
- **触发条件**: 用户点赞某个 Post
- **推送对象**:
  - 当前正在查看该 Post 详情页的所有用户
  - 当前在时间线页面且该 Post 在可见范围内的用户
- **推送内容**: 更新的点赞数 + 点赞用户信息

### 3. 订阅管理
- **页面订阅**: 客户端打开某个 Post 详情页时，发送订阅消息
- **页面取消订阅**: 客户端离开页面时，发送取消订阅消息
- **自动清理**: 连接断开时自动清理所有订阅

---

## Domain Models 设计

### 1. NotificationEvent (密封接口)

```kotlin
sealed interface NotificationEvent {
    /**
     * 新 Post 创建事件
     * 推送给所有在线用户
     */
    data class NewPostCreated(
        val postId: PostId,
        val authorId: UserId,
        val authorDisplayName: String,
        val content: String,
        val createdAt: Long
    ) : NotificationEvent

    /**
     * Post 被点赞事件
     * 推送给订阅该 Post 的用户
     */
    data class PostLiked(
        val postId: PostId,
        val likedByUserId: UserId,
        val likedByDisplayName: String,
        val newLikeCount: Int
    ) : NotificationEvent

    /**
     * Post 被评论/回复事件（未来扩展）
     */
    data class PostCommented(
        val postId: PostId,
        val commentedByUserId: UserId,
        val commentedByDisplayName: String,
        val commentId: PostId,
        val commentPreview: String
    ) : NotificationEvent
}
```

### 2. NotificationTarget

```kotlin
/**
 * 通知推送目标
 */
sealed interface NotificationTarget {
    /** 广播给所有在线用户 */
    data object Everyone : NotificationTarget

    /** 推送给特定用户 */
    data class SpecificUser(val userId: UserId) : NotificationTarget

    /** 推送给订阅特定 Post 的用户 */
    data class PostSubscribers(val postId: PostId) : NotificationTarget
}
```

### 3. WebSocketClientMessage (客户端消息)

```kotlin
/**
 * 客户端发送的消息类型
 */
sealed interface WebSocketClientMessage {
    /**
     * 订阅特定 Post 的更新（进入 Post 详情页时）
     */
    data class SubscribeToPost(val postId: PostId) : WebSocketClientMessage

    /**
     * 取消订阅 Post（离开 Post 详情页时）
     */
    data class UnsubscribeFromPost(val postId: PostId) : WebSocketClientMessage

    /**
     * 心跳包（保持连接活跃）
     */
    data object Ping : WebSocketClientMessage
}
```

---

## Repository 接口设计

### NotificationRepository

```kotlin
interface NotificationRepository {
    /**
     * 广播新 Post 创建事件
     * 推送给所有在线用户
     */
    suspend fun broadcastNewPost(event: NotificationEvent.NewPostCreated)

    /**
     * 通知 Post 被点赞
     * 推送给订阅该 Post 的用户
     */
    suspend fun notifyPostLiked(event: NotificationEvent.PostLiked)

    /**
     * 通知 Post 被评论
     * 推送给 Post 作者和订阅者
     */
    suspend fun notifyPostCommented(event: NotificationEvent.PostCommented)
}
```

---

## Use Cases 设计

### 1. BroadcastPostCreatedUseCase

```kotlin
class BroadcastPostCreatedUseCase(
    private val notificationRepository: NotificationRepository
) {
    /**
     * 当新的顶层 Post 创建时触发
     *
     * 业务规则：
     * 1. 仅广播顶层 Post（非回复）
     * 2. 推送给所有在线用户
     * 3. 失败不影响 Post 创建主流程（异步推送）
     */
    suspend fun execute(
        postId: PostId,
        authorId: UserId,
        authorDisplayName: String,
        content: String,
        createdAt: Long
    ) {
        val event = NotificationEvent.NewPostCreated(
            postId = postId,
            authorId = authorId,
            authorDisplayName = authorDisplayName,
            content = content,
            createdAt = createdAt
        )

        // 异步推送，失败不阻塞主流程
        notificationRepository.broadcastNewPost(event)
    }
}
```

### 2. BroadcastPostLikedUseCase

```kotlin
class BroadcastPostLikedUseCase(
    private val notificationRepository: NotificationRepository
) {
    /**
     * 当 Post 被点赞时触发
     *
     * 业务规则：
     * 1. 推送给订阅该 Post 的用户
     * 2. 包含最新点赞数
     * 3. 失败不影响点赞主流程
     */
    suspend fun execute(
        postId: PostId,
        likedByUserId: UserId,
        likedByDisplayName: String,
        newLikeCount: Int
    ) {
        val event = NotificationEvent.PostLiked(
            postId = postId,
            likedByUserId = likedByUserId,
            likedByDisplayName = likedByDisplayName,
            newLikeCount = newLikeCount
        )

        notificationRepository.notifyPostLiked(event)
    }
}
```

---

## Infrastructure 实现设计

### 1. WebSocketConnectionManager

```kotlin
/**
 * 管理所有 WebSocket 连接和订阅关系
 *
 * 职责：
 * 1. 维护用户连接 Map: UserId -> Set<WebSocketSession>
 * 2. 维护 Post 订阅 Map: PostId -> Set<WebSocketSession>
 * 3. 提供订阅/取消订阅方法
 * 4. 提供广播方法
 * 5. 自动清理断开的连接
 */
class WebSocketConnectionManager {
    // 用户连接映射 (一个用户可能有多个设备连接)
    private val userSessions: MutableMap<UserId, MutableSet<WebSocketServerSession>>

    // Post 订阅映射 (一个 Post 可能被多个用户订阅)
    private val postSubscriptions: MutableMap<PostId, MutableSet<WebSocketServerSession>>

    // 会话到用户的反向映射 (用于连接断开时清理)
    private val sessionToUser: MutableMap<WebSocketServerSession, UserId>

    fun addUserSession(userId: UserId, session: WebSocketServerSession)
    fun removeUserSession(session: WebSocketServerSession)

    fun subscribeToPost(userId: UserId, postId: PostId, session: WebSocketServerSession)
    fun unsubscribeFromPost(postId: PostId, session: WebSocketServerSession)

    suspend fun broadcastToAll(message: String)
    suspend fun sendToUser(userId: UserId, message: String)
    suspend fun sendToPostSubscribers(postId: PostId, message: String)
}
```

### 2. InMemoryNotificationRepository

```kotlin
class InMemoryNotificationRepository(
    private val connectionManager: WebSocketConnectionManager,
    private val logger: Logger
) : NotificationRepository {

    override suspend fun broadcastNewPost(event: NotificationEvent.NewPostCreated) {
        val message = Json.encodeToString(
            NotificationMessage(
                type = "new_post",
                data = event
            )
        )
        connectionManager.broadcastToAll(message)
    }

    override suspend fun notifyPostLiked(event: NotificationEvent.PostLiked) {
        val message = Json.encodeToString(
            NotificationMessage(
                type = "post_liked",
                data = event
            )
        )
        connectionManager.sendToPostSubscribers(event.postId, message)
    }

    override suspend fun notifyPostCommented(event: NotificationEvent.PostCommented) {
        // 未来实现
    }
}
```

---

## Transport Layer 设计

### WebSocket Endpoint

```kotlin
fun Route.notificationWebSocket(
    connectionManager: WebSocketConnectionManager
) {
    authenticate("auth-jwt") {
        webSocket("/v1/notifications/ws") {
            val principal = call.principal<UserPrincipal>()
                ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))

            val userId = principal.userId

            // 注册用户连接
            connectionManager.addUserSession(userId, this)

            try {
                // 发送连接成功消息
                send(Frame.Text("""{"type":"connected","userId":"${userId.value}"}"""))

                // 处理客户端消息
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        handleClientMessage(frame.readText(), userId, this, connectionManager)
                    }
                }
            } catch (e: Exception) {
                logger.error("WebSocket error for user ${userId.value}", e)
            } finally {
                // 清理连接
                connectionManager.removeUserSession(this)
            }
        }
    }
}

private suspend fun handleClientMessage(
    text: String,
    userId: UserId,
    session: DefaultWebSocketServerSession,
    connectionManager: WebSocketConnectionManager
) {
    try {
        val message = Json.decodeFromString<WebSocketClientMessageDto>(text)
        when (message.type) {
            "subscribe_post" -> {
                val postId = PostId(message.postId ?: return)
                connectionManager.subscribeToPost(userId, postId, session)
                session.send(Frame.Text("""{"type":"subscribed","postId":"${postId.value}"}"""))
            }
            "unsubscribe_post" -> {
                val postId = PostId(message.postId ?: return)
                connectionManager.unsubscribeFromPost(postId, session)
                session.send(Frame.Text("""{"type":"unsubscribed","postId":"${postId.value}"}"""))
            }
            "ping" -> {
                session.send(Frame.Text("""{"type":"pong"}"""))
            }
        }
    } catch (e: Exception) {
        logger.error("Failed to parse client message", e)
    }
}
```

---

## 集成到现有 Use Cases

### 修改 CreatePostUseCase

```kotlin
class CreatePostUseCase(
    private val postRepository: PostRepository,
    private val broadcastPostCreatedUseCase: BroadcastPostCreatedUseCase
) {
    suspend fun execute(command: CreatePostCommand): Either<PostError, Post> {
        // 原有创建逻辑
        val result = postRepository.create(post)

        // 如果是顶层 Post，广播通知
        result.onRight { createdPost ->
            if (createdPost.parentId == null) {
                // 异步推送，不阻塞主流程
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        broadcastPostCreatedUseCase.execute(
                            postId = createdPost.id,
                            authorId = createdPost.authorId,
                            authorDisplayName = "...", // 从 User 获取
                            content = createdPost.content.value,
                            createdAt = createdPost.createdAt
                        )
                    } catch (e: Exception) {
                        logger.error("Failed to broadcast new post", e)
                    }
                }
            }
        }

        return result
    }
}
```

### 修改 LikePostUseCase

```kotlin
class LikePostUseCase(
    private val postRepository: PostRepository,
    private val broadcastPostLikedUseCase: BroadcastPostLikedUseCase
) {
    suspend fun execute(userId: UserId, postId: PostId): Either<LikeError, PostStats> {
        val result = postRepository.likePost(userId, postId)

        // 点赞成功后推送通知
        result.onRight { stats ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    broadcastPostLikedUseCase.execute(
                        postId = postId,
                        likedByUserId = userId,
                        likedByDisplayName = "...", // 从 User 获取
                        newLikeCount = stats.likeCount
                    )
                } catch (e: Exception) {
                    logger.error("Failed to broadcast post liked", e)
                }
            }
        }

        return result
    }
}
```

---

## 客户端使用示例

### 连接 WebSocket

```typescript
// 建立连接
const ws = new WebSocket('ws://localhost:8080/v1/notifications/ws', {
  headers: { Authorization: `Bearer ${token}` }
});

ws.onopen = () => {
  console.log('Connected to notification server');
};

ws.onmessage = (event) => {
  const message = JSON.parse(event.data);

  switch (message.type) {
    case 'new_post':
      handleNewPost(message.data);
      break;
    case 'post_liked':
      handlePostLiked(message.data);
      break;
    case 'connected':
      console.log('Connected as user:', message.userId);
      break;
  }
};

ws.onerror = (error) => {
  console.error('WebSocket error:', error);
};

ws.onclose = () => {
  console.log('Disconnected from notification server');
  // 自动重连逻辑
};
```

### 订阅 Post

```typescript
// 进入 Post 详情页时
function enterPostDetailPage(postId: string) {
  ws.send(JSON.stringify({
    type: 'subscribe_post',
    postId: postId
  }));
}

// 离开页面时
function leavePostDetailPage(postId: string) {
  ws.send(JSON.stringify({
    type: 'unsubscribe_post',
    postId: postId
  }));
}
```

### 处理通知

```typescript
function handleNewPost(data: NewPostCreatedEvent) {
  // 在时间线顶部显示"有新内容"提示
  showNewPostBanner({
    postId: data.postId,
    author: data.authorDisplayName,
    content: data.content
  });
}

function handlePostLiked(data: PostLikedEvent) {
  // 更新 UI 中的点赞数
  updatePostLikeCount(data.postId, data.newLikeCount);

  // 可选：显示点赞动画
  showLikeAnimation(data.postId, data.likedByDisplayName);
}
```

---

## 性能和扩展性考虑

### 当前实现（单机版）

- **连接管理**: 内存中维护 `Map<UserId, Set<Session>>`
- **订阅管理**: 内存中维护 `Map<PostId, Set<Session>>`
- **消息推送**: 直接遍历 sessions 发送
- **适用规模**: < 10,000 并发连接

### 未来扩展（分布式）

当需要支持更大规模时，可扩展为：

1. **Redis Pub/Sub**:
   - 使用 Redis 作为消息总线
   - 多个服务器实例订阅同一 channel
   - 实现跨服务器的消息广播

2. **Redis 连接管理**:
   - 使用 Redis Set 存储 `userId -> serverInstanceId`
   - 消息路由到正确的服务器实例

3. **负载均衡**:
   - WebSocket 连接使用 sticky session
   - 或使用支持 WebSocket 的负载均衡器（Nginx, HAProxy）

---

## 错误处理和可靠性

### 1. 推送失败处理

```kotlin
try {
    broadcastPostCreatedUseCase.execute(...)
} catch (e: Exception) {
    // 记录错误但不阻塞主流程
    logger.error("Failed to broadcast notification", e)
}
```

### 2. 连接断开清理

```kotlin
finally {
    // WebSocket 连接断开时自动清理所有订阅
    connectionManager.removeUserSession(session)
}
```

### 3. 心跳保活

客户端定期发送 ping 消息，服务器响应 pong，防止连接超时。

---

## 安全考虑

### 1. 认证

- WebSocket 连接必须携带有效 JWT Token
- 使用 Ktor 的 `authenticate("auth-jwt")` 保护 WebSocket endpoint

### 2. 授权

- 用户只能订阅公开 Post（未来可扩展私密 Post 权限检查）
- 推送消息不包含敏感信息（邮箱等）

### 3. 速率限制

- 限制客户端发送消息频率（防止滥用）
- 限制单个用户订阅的 Post 数量（防止资源耗尽）

---

## 测试策略

### 1. 单元测试

- `WebSocketConnectionManager` 的订阅管理逻辑
- `InMemoryNotificationRepository` 的推送逻辑

### 2. 集成测试

- WebSocket 连接和消息收发
- 多客户端订阅和广播

### 3. 负载测试

- 模拟大量并发连接
- 测试消息推送性能

---

## 实现清单

### Domain Layer
- [ ] NotificationEvent 密封接口
- [ ] NotificationTarget 定义
- [ ] WebSocketClientMessage 定义
- [ ] NotificationRepository 接口

### Infrastructure Layer
- [ ] WebSocketConnectionManager 实现
- [ ] InMemoryNotificationRepository 实现

### Use Cases
- [ ] BroadcastPostCreatedUseCase
- [ ] BroadcastPostLikedUseCase
- [ ] 修改 CreatePostUseCase 集成通知
- [ ] 修改 LikePostUseCase 集成通知

### Transport Layer
- [ ] NotificationWebSocket endpoint
- [ ] WebSocketClientMessageDto
- [ ] NotificationMessageDto

### Configuration
- [ ] 添加 WebSocket 依赖
- [ ] Koin DI 配置
- [ ] Routing 配置

---

## 设计完成 🎉

这个设计完全遵循现有的 Hexagonal Architecture，核心业务逻辑在 Domain 层，WebSocket 实现在 Infrastructure 层，协议转换在 Transport 层。推送功能不会阻塞主业务流程，失败时优雅降级。
