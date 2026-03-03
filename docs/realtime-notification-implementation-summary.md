# 实时推送通知功能 - 实现总结

## 概述

已完成基于 SSE (Server-Sent Events) + REST 的实时推送通知功能，遵循项目的 Hexagonal Architecture 和 DDD 原则。

**功能特性**：
- SSE 事件流：`GET /v1/notifications/stream`（单向推送）
- 新 Post 创建时全局广播通知
- Post 点赞时推送给订阅者
- 客户端通过 REST 端点订阅/取消订阅特定 Post
- 私信通知：新消息、已读回执、消息撤回
- 打字状态通过 REST 端点发送
- 在线状态：快照 + 增量协议
- 自动连接管理和清理（Channel-based）
- 异步推送，不阻塞主业务流程

---

## 架构设计

遵循 Hexagonal Architecture：

```
Domain Layer (纯 Kotlin)
    ↓
Infrastructure Layer (SSE 实现)
    ↓
Transport Layer (SSE stream + REST 命令端点)
```

**关键设计决策**：
1. 通知推送失败不影响主业务流程（异步执行）
2. SSE stream 为服务端→客户端单向推送
3. 客户端命令（订阅、打字）通过独立 REST 端点，享有 HTTP 语义
4. Post 订阅为用户级（`PostId → Set<UserId>`），支持多设备同步
5. Channel-based 推送，天然背压和生命周期管理

---

## 文件清单

### 1. Domain Layer

#### `src/main/kotlin/domain/model/Notification.kt`
- `NotificationEvent` 密封接口：定义各种通知事件
  - `NewPostCreated`: 新 Post 创建事件
  - `PostLiked`: Post 点赞事件
  - `PostCommented`: Post 评论事件
  - `NewMessageReceived`: 新私信事件
  - `MessagesRead`: 消息已读事件
  - `MessageRecalled`: 消息撤回事件
  - `TypingIndicator`: 打字状态事件
- `NotificationTarget`: 推送目标定义

#### `src/main/kotlin/domain/repository/NotificationRepository.kt`
- `NotificationRepository` 接口：定义通知推送契约（7 个方法）

#### `src/main/kotlin/domain/service/SessionNotifier.kt`
- `SessionNotifier` 接口：会话撤销通知端口

#### `src/main/kotlin/domain/usecase/BroadcastPostCreatedUseCase.kt`
- 广播新 Post 创建事件 Use Case

#### `src/main/kotlin/domain/usecase/BroadcastPostLikedUseCase.kt`
- 广播 Post 点赞事件 Use Case

### 2. Infrastructure Layer

#### `src/main/kotlin/infrastructure/sse/SseConnectionManager.kt`
- SSE 连接管理器
- 功能：
  - 管理用户连接映射 (`UserId → Set<connectionId>`)，每个连接持有一个 `Channel<ServerSentEvent>`
  - 管理 Post 订阅映射 (`PostId → Set<UserId>`)（用户级）
  - 提供广播和定向推送方法
  - 自动清理失败的连接（惰性清理）
  - 单调递增事件 ID（`AtomicLong`）
- 线程安全：使用 `ConcurrentHashMap`

#### `src/main/kotlin/infrastructure/sse/SseSessionNotifier.kt`
- `SessionNotifier` 端口实现
- 发送 `auth_revoked` SSE 事件

#### `src/main/kotlin/infrastructure/repository/InMemoryNotificationRepository.kt`
- 内存型通知 Repository 实现
- 将领域事件 JSON 序列化为 SSE 事件
- 通过 `SseConnectionManager` 推送类型化事件

### 3. Transport Layer

#### `src/main/kotlin/features/notification/NotificationSse.kt`
- SSE 流端点：`GET /v1/notifications/stream`
- 需要 JWT 认证
- 连接生命周期：connected → presence_snapshot → heartbeat loop → channel forwarding
- 连接/断开时处理在线状态广播

#### `src/main/kotlin/features/notification/NotificationCommandRoutes.kt`
- Post 订阅 REST 端点：
  - `POST /v1/notifications/posts/{postId}/subscribe`
  - `DELETE /v1/notifications/posts/{postId}/subscribe`

#### `src/main/kotlin/features/notification/NotificationSchema.kt`
- `TypingRequest` DTO

#### `src/main/kotlin/features/messaging/MessagingRoutes.kt`
- 打字状态端点：`PUT /v1/messaging/conversations/{conversationId}/typing`

### 4. Configuration

#### `src/main/kotlin/plugins/SSE.kt`
- SSE 插件安装

#### `src/main/kotlin/core/di/NotificationModule.kt`
- 通知模块 DI 配置
- 注册 `SseConnectionManager`
- 注册 `NotificationRepository` 实现
- 注册通知 Use Cases

---

## API 端点

### SSE Stream

```
GET http://localhost:8080/v1/notifications/stream
Authorization: Bearer <jwt>
Accept: text/event-stream
```

**认证**: 需要 JWT Token

**服务端→客户端事件**（SSE `event` 字段）:

| 事件类型 | 说明 |
|---------|------|
| `connected` | 连接成功确认 |
| `presence_snapshot` | 对话对端在线状态快照（保证必发） |
| `user_presence_changed` | 对端上线/下线增量事件 |
| `new_post` | 新 Post 创建 |
| `post_liked` | Post 被点赞 |
| `post_commented` | Post 被评论 |
| `new_message` | 收到新私信 |
| `messages_read` | 消息已读回执 |
| `message_recalled` | 消息被撤回 |
| `typing_indicator` | 打字状态 |
| `auth_revoked` | 会话被撤销，需要重新登录 |

**心跳**: `:heartbeat` 注释行，每 30 秒

### REST 命令端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/v1/notifications/posts/{postId}/subscribe` | 订阅 Post 更新 |
| `DELETE` | `/v1/notifications/posts/{postId}/subscribe` | 取消订阅 |
| `PUT` | `/v1/messaging/conversations/{conversationId}/typing` | 发送打字状态 |

**打字状态请求体**:
```json
{ "isTyping": true }
```

---

## 使用流程

### 服务端触发流程

1. **新 Post 创建**:
   ```
   User creates Post
       ↓
   CreatePostUseCase.invoke()
       ↓
   Post saved to database
       ↓
   BroadcastPostCreatedUseCase.execute() (async)
       ↓
   NotificationRepository.broadcastNewPost()
       ↓
   SseConnectionManager.broadcastToAll("new_post", data)
       ↓
   All connected clients receive SSE event
   ```

2. **Post 点赞**:
   ```
   User likes Post
       ↓
   LikePostUseCase.invoke()
       ↓
   Like saved, PostStats updated
       ↓
   BroadcastPostLikedUseCase.execute() (async)
       ↓
   NotificationRepository.notifyPostLiked()
       ↓
   SseConnectionManager.sendToPostSubscribers(postId, "post_liked", data)
       ↓
   Subscribers receive SSE event
   ```

### 客户端使用流程

1. **建立 SSE 连接** — 使用 `GET /v1/notifications/stream` + `Authorization` 头
2. **收到事件流** — 按 SSE `event` 字段分发处理
3. **订阅 Post** — `POST /v1/notifications/posts/{postId}/subscribe`
4. **取消订阅** — `DELETE /v1/notifications/posts/{postId}/subscribe`
5. **发送打字状态** — `PUT /v1/messaging/conversations/{conversationId}/typing`

---

## 性能考虑

### 当前实现（单机版）

- **适用规模**: < 10,000 并发连接
- **内存管理**: ConcurrentHashMap 维护连接和订阅映射
- **消息推送**: 遍历 Channel 发送
- **背压**: Channel.BUFFERED 天然支持
- **优点**: 简单、无外部依赖、延迟低、HTTP 原生

### 未来优化（分布式版）

1. **Redis Pub/Sub** — 跨服务器广播
2. **Redis 连接路由** — `userId → serverInstanceId`
3. **HTTP 负载均衡** — SSE 是标准 HTTP，无需特殊负载均衡器

---

## 监控和日志

### 已实现日志

1. **连接管理**: 用户连接/断开、连接数统计
2. **订阅管理**: 订阅/取消订阅操作、订阅者数量
3. **消息推送**: 广播成功/失败统计、清理的过期连接数
4. **在线状态**: 上线/下线广播
5. **异常处理**: SSE 错误、消息发送失败

### 建议添加的监控指标

- 在线用户数和活跃 SSE 连接数
- 订阅的 Post 数量
- 消息推送速率和失败率
- 平均推送延迟

---

## 客户端示例文档

详细的客户端使用示例和最佳实践请参考：
- `realtime-notification-client-examples.md`

包含：
- SSE 连接和事件处理
- Kotlin Multiplatform 客户端示例
- REST 命令调用
- 自动重连和指数退避
- 订阅管理最佳实践

---

## 总结

**已完成**:
- SSE 事件流推送所有实时通知
- REST 端点处理客户端命令
- 在线状态（快照 + 增量）
- 打字状态
- 会话撤销推送

**架构优势**:
- 遵循 Hexagonal Architecture，Domain 层无框架依赖
- SSE 比 WebSocket 更简单：无帧协议、HTTP 原生、天然代理兼容
- Channel-based 推送：天然背压、线程安全、清晰的生命周期
- 用户级 Post 订阅：多设备同步

**后续优化方向**:
- `Last-Event-ID` 重放支持
- 分布式部署（Redis Pub/Sub）
- 更多事件类型
- 离线消息和历史记录
