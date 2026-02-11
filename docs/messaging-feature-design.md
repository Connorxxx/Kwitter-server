# 私信（DM）功能设计文档

**更新时间**: 2026-02-11
**状态**: 已实现 v1
**前置阅读**: [realtime-notification-design.md](./realtime-notification-design.md)

---

## 架构概览

遵循 Hexagonal Architecture，私信功能横跨四层：

```
┌─────────────────────────────────────────────────────────────┐
│                        Domain Layer                         │
│              (纯 Kotlin，无框架依赖)                          │
├─────────────────────────────────────────────────────────────┤
│  Models:                                                    │
│    - ConversationId, MessageId (value class)                │
│    - MessageContent (2000 char limit, validated)            │
│    - DmPermission (EVERYONE | MUTUAL_FOLLOW)                │
│    - Conversation (canonical participant ordering)          │
│    - Message (text + optional image)                        │
│    - ConversationDetail (conversation + otherUser + last    │
│      message + unread count)                                │
│                                                             │
│  Errors:                                                    │
│    - MessageError (sealed interface, 8 variants)            │
│                                                             │
│  Repository (Port):                                         │
│    - MessageRepository                                      │
│                                                             │
│  Service (Port):                                            │
│    - PushNotificationService (FCM 预留)                     │
│                                                             │
│  Use Cases:                                                 │
│    - SendMessageUseCase                                     │
│    - GetConversationsUseCase                                │
│    - GetMessagesUseCase                                     │
│    - MarkConversationReadUseCase                            │
│    - NotifyNewMessageUseCase                                │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                        Data Layer                           │
│                 (Exposed ORM, PostgreSQL)                    │
├─────────────────────────────────────────────────────────────┤
│  Tables:                                                    │
│    - ConversationsTable (唯一索引: participant1+participant2)│
│    - MessagesTable (索引: conversation+created, read状态)   │
│                                                             │
│  Repository:                                                │
│    - ExposedMessageRepository                               │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   Infrastructure Layer                       │
├─────────────────────────────────────────────────────────────┤
│  - NoOpPushNotificationService (FCM stub)                   │
│  - InMemoryNotificationRepository (扩展：新增                │
│    notifyNewMessage / notifyMessagesRead)                    │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      Transport Layer                         │
│                    (REST Endpoints)                          │
├─────────────────────────────────────────────────────────────┤
│  GET  /v1/conversations              → 对话列表              │
│  POST /v1/conversations/messages     → 发送消息              │
│  GET  /v1/conversations/{id}/messages → 消息历史             │
│  PUT  /v1/conversations/{id}/read    → 标记已读              │
│                                                             │
│  WebSocket 通知 (复用 /v1/notifications/ws):                │
│    - type: "new_message"   → 推给接收者                     │
│    - type: "messages_read" → 推给发送者                     │
└─────────────────────────────────────────────────────────────┘
```

---

## 关键设计决策

### 1. Canonical Participant Ordering（对话去重）

**问题**: 用户 A→B 和 B→A 应该是同一个对话，如何保证数据库中只有一行？

**方案**: 始终将 `participant1Id < participant2Id`（字典序），存储时强制排序。

```kotlin
fun canonicalParticipants(userId1: UserId, userId2: UserId): Pair<UserId, UserId> {
    return if (userId1.value < userId2.value) userId1 to userId2
    else userId2 to userId1
}
```

配合唯一索引 `uk_conversation_participants(participant1_id, participant2_id)`，数据库层也保证不会创建重复对话。

**并发安全**: `findOrCreateConversation` 先查再建，唯一索引兜底。如果并发创建触发重复键错误，catch 后重新查询即可（当前实现简化为单次 insert，生产环境高并发时需加 retry）。

### 2. Single MessageRepository（而非拆分 Conversation + Message）

**原因**: 发送消息时需原子更新 `Conversation.lastMessageAt` + 插入 `Message`，同一事务边界。拆分 repo 会导致跨 repo 事务协调，增加复杂度。

### 3. 无独立 WebSocket 端点

**原因**: 私信通知复用现有 `/v1/notifications/ws` 通道，通过 `connectionManager.sendToUser()` 定向推送。避免客户端维护两条 WebSocket 连接。

消息类型通过 `type` 字段区分：`"new_message"` / `"messages_read"`。

### 4. FCM 作为 Domain Port

```
domain/service/PushNotificationService.kt  (接口)
    ↓
infrastructure/service/NoOpPushNotificationService.kt  (当前：空实现)
infrastructure/service/FcmPushNotificationService.kt   (未来：FCM 实现)
```

切换时只需在 `MessagingModule.kt` 中替换 Koin 绑定：

```kotlin
// 当前
single<PushNotificationService> { NoOpPushNotificationService() }

// 未来
single<PushNotificationService> { FcmPushNotificationService(get()) }
```

### 5. DM Permission（预留字段）

当前默认 `EVERYONE` 可以发私信。`SendMessageUseCase` 中预留了 mutual follow 检查的注释代码：

```kotlin
// 未来启用：
// val isMutualFollow = userRepository.isFollowing(senderId, recipientId)
//     && userRepository.isFollowing(recipientId, senderId)
// ensure(isMutualFollow) { MessageError.DmPermissionDenied }
```

**启用步骤**:
1. `UsersTable` 添加 `dm_permission` 列（varchar, default "EVERYONE"）
2. `User` data class 添加 `dmPermission: DmPermission` 字段
3. `SendMessageUseCase` 取消注释并查询 recipient 的 `dmPermission`

---

## 数据模型

### 数据库表

```sql
-- 对话表
CREATE TABLE conversations (
    id              VARCHAR(36) PRIMARY KEY,
    participant1_id VARCHAR(36) REFERENCES users(id),  -- 字典序较小
    participant2_id VARCHAR(36) REFERENCES users(id),  -- 字典序较大
    last_message_id VARCHAR(36),
    last_message_at BIGINT,
    created_at      BIGINT,
    UNIQUE (participant1_id, participant2_id)
);

CREATE INDEX idx_conversations_p1_last ON conversations (participant1_id, last_message_at);
CREATE INDEX idx_conversations_p2_last ON conversations (participant2_id, last_message_at);

-- 消息表
CREATE TABLE messages (
    id              VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) REFERENCES conversations(id),
    sender_id       VARCHAR(36) REFERENCES users(id),
    content         VARCHAR(2000),
    image_url       VARCHAR(256),
    read_at         BIGINT,          -- NULL = 未读, 时间戳 = 已读时间
    created_at      BIGINT
);

CREATE INDEX idx_messages_conversation_created ON messages (conversation_id, created_at);
CREATE INDEX idx_messages_conversation_read ON messages (conversation_id, sender_id, read_at);
```

### Domain Models

```kotlin
// Value objects
@JvmInline value class ConversationId(val value: String)
@JvmInline value class MessageId(val value: String)
@JvmInline value class MessageContent private constructor(val value: String)  // max 2000

enum class DmPermission { EVERYONE, MUTUAL_FOLLOW }

// Entities
data class Conversation(id, participant1Id, participant2Id, lastMessageId?, lastMessageAt?, createdAt)
data class Message(id, conversationId, senderId, content, imageUrl?, readAt?, createdAt)

// Aggregates
data class ConversationDetail(conversation, otherUser: User, lastMessage?, unreadCount: Int)
```

### Error Types

```kotlin
sealed interface MessageError {
    data object EmptyContent                             // 空内容
    data class ContentTooLong(actual, max)                // 超过 2000 字符
    data class ConversationNotFound(conversationId)       // 对话不存在
    data class MessageNotFound(messageId)                 // 消息不存在
    data class NotConversationParticipant(userId, convId) // 非参与者（403）
    data object CannotMessageSelf                         // 不能给自己发消息
    data class RecipientNotFound(recipientId)             // 接收者不存在
    data object DmPermissionDenied                        // DM 权限拒绝
}
```

---

## Use Case 详解

### SendMessageUseCase

**入口**: `POST /v1/conversations/messages`

**流程**:
1. 校验 `senderId != recipientId`
2. 校验 recipient 存在（`UserRepository.findById`）
3. 校验 DM 权限（当前跳过）
4. 校验 content（`MessageContent()` 返回 Either）
5. `findOrCreateConversation(senderId, recipientId)` — canonical ordering
6. 插入 Message + 更新 Conversation.lastMessageAt（同一事务）
7. 返回 `SendMessageResult(message, conversation)`

**调用方额外操作（Route 层，异步）**:
- `appScope.launch { notifyNewMessageUseCase.execute(...) }`

### GetConversationsUseCase

**入口**: `GET /v1/conversations`

**流程**: 查询用户参与的所有对话，按 `lastMessageAt DESC` 排序。每个对话附带：
- 对方用户信息（displayName, username, avatarUrl）
- 最后一条消息预览
- 未读消息数

### GetMessagesUseCase

**入口**: `GET /v1/conversations/{id}/messages`

**流程**:
1. 查询 conversation 是否存在
2. 校验当前用户是否为参与者（防止越权查看他人对话）
3. 返回分页 messages（按 createdAt DESC）

### MarkConversationReadUseCase

**入口**: `PUT /v1/conversations/{id}/read`

**流程**: 将对话中所有「对方发送的、readAt = NULL」的消息设为已读。

### NotifyNewMessageUseCase

**触发方式**: 由 Route 层 `appScope.launch {}` 异步调用

**流程**:
1. 构造 `NotificationEvent.NewMessageReceived` 事件
2. `notificationRepository.notifyNewMessage(recipientId, event)` — WebSocket 推送
3. `pushNotificationService.sendPush(recipientId, ...)` — FCM（当前 NoOp）

**错误处理**: catch all + log，不传播异常。推送失败不影响消息发送成功。

---

## 文件清单

### 新增文件

| 路径 | 职责 |
|------|------|
| `domain/model/Conversation.kt` | Value objects + entities |
| `domain/failure/MessageErrors.kt` | 错误类型 |
| `domain/service/PushNotificationService.kt` | FCM port |
| `domain/repository/MessageRepository.kt` | Repository port |
| `domain/usecase/SendMessageUseCase.kt` | 发送消息 |
| `domain/usecase/GetConversationsUseCase.kt` | 对话列表 |
| `domain/usecase/GetMessagesUseCase.kt` | 消息历史 |
| `domain/usecase/MarkConversationReadUseCase.kt` | 标记已读 |
| `domain/usecase/NotifyNewMessageUseCase.kt` | 推送通知 |
| `data/db/schema/MessagingTable.kt` | 数据库表定义 |
| `data/db/mapping/MessageMapping.kt` | Row → Domain 映射 |
| `data/repository/ExposedMessageRepository.kt` | Repository 实现 |
| `infrastructure/service/NoOpPushNotificationService.kt` | FCM stub |
| `features/messaging/MessagingSchema.kt` | Request/Response DTOs |
| `features/messaging/MessagingMappers.kt` | Domain ↔ DTO 映射 |
| `features/messaging/MessagingRoutes.kt` | REST 路由 |
| `core/di/MessagingModule.kt` | Koin 模块 |

### 修改文件

| 路径 | 修改内容 |
|------|----------|
| `domain/model/Notification.kt` | 新增 `NewMessageReceived` + `MessagesRead` 事件 |
| `domain/repository/NotificationRepository.kt` | 新增 `notifyNewMessage` + `notifyMessagesRead` 方法 |
| `infrastructure/repository/InMemoryNotificationRepository.kt` | 实现两个新方法 |
| `data/db/DatabaseFactory.kt` | 表创建列表添加 `ConversationsTable`, `MessagesTable` |
| `plugins/Routing.kt` | 注入 messaging use cases，注册 `messagingRoutes()` |
| `Frameworks.kt` | Koin modules 添加 `messagingModule` |

---

## 索引与查询性能

| 查询场景 | 使用索引 | 说明 |
|---------|----------|------|
| 查对话列表 (p1) | `idx_conversations_p1_last` | WHERE participant1_id = ? ORDER BY last_message_at DESC |
| 查对话列表 (p2) | `idx_conversations_p2_last` | WHERE participant2_id = ? ORDER BY last_message_at DESC |
| 对话去重 | `uk_conversation_participants` | UNIQUE(participant1_id, participant2_id) |
| 消息分页 | `idx_messages_conversation_created` | WHERE conversation_id = ? ORDER BY created_at DESC |
| 未读计数 | `idx_messages_conversation_read` | WHERE conversation_id = ? AND sender_id = ? AND read_at IS NULL |

---

## 通知事件格式

### new_message（推给接收者）

```json
{
  "type": "new_message",
  "data": {
    "messageId": "msg-uuid",
    "conversationId": "conv-uuid",
    "senderDisplayName": "Connor",
    "senderUsername": "connor",
    "contentPreview": "Hey, how's it going?",
    "timestamp": 1707600000000
  }
}
```

### messages_read（推给发送者）

```json
{
  "type": "messages_read",
  "data": {
    "conversationId": "conv-uuid",
    "readByUserId": "user-uuid",
    "timestamp": 1707600000000
  }
}
```

---

## 未来扩展点

### 短期

- [ ] `dmPermission` 字段落地（User 表 + SendMessageUseCase 检查）
- [ ] FCM 推送实现（替换 NoOpPushNotificationService）
- [ ] 消息删除（逻辑删除，`deletedAt` 字段）
- [ ] 打字状态指示器（WebSocket 短暂事件，不持久化）
- [ ] 对话静音（`muted_at` 字段，跳过推送）

### 中期

- [ ] 消息搜索（PostgreSQL FTS on messages.content）
- [ ] 图片消息缩略图（复用现有 Media 系统）
- [ ] 消息回复/引用（`replyToMessageId` 字段）
- [ ] 在线状态指示器（基于 WebSocket 连接状态）

### 长期

- [ ] 群聊（Conversation 模型扩展，`ConversationParticipantsTable`）
- [ ] 端到端加密（Signal Protocol）
- [ ] 消息撤回（时间窗口内）
- [ ] Redis 分布式推送（多实例部署）

---

## 调试指南

### 常见问题

**Q: 发消息返回 404 `RECIPIENT_NOT_FOUND`**
→ 确认 recipientId 是有效的 UserId（不是 username）

**Q: 对话列表返回空，但消息确实存在**
→ 检查 conversations 表的 `last_message_at` 是否被正确更新（排序依赖此字段，NULL 排在最后）

**Q: 未读数不准确**
→ 未读计数逻辑：`WHERE conversation_id = ? AND sender_id = <other_user> AND read_at IS NULL`
→ 确认 `markConversationAsRead` 正确地只标记对方发送的消息

**Q: WebSocket 收不到 new_message 通知**
→ 1) 确认接收者已连接 WebSocket（`/v1/notifications/ws`）
→ 2) 检查 `WebSocketConnectionManager.sendToUser()` 是否找到了对应 session
→ 3) 通知是 fire-and-forget，查看服务端日志中是否有 "Notified new message" 或错误

### 关键日志点

```
SendMessageUseCase    - "Sending message: senderId={}, recipientId={}"
SendMessageUseCase    - "Message sent: messageId={}, conversationId={}"
ExposedMessageRepo    - "Created conversation: id={}, p1={}, p2={}"
ExposedMessageRepo    - "Saved message: id={}, conversationId={}"
ExposedMessageRepo    - "Marked {} messages as read: conversationId={}, userId={}"
NotifyNewMessageUC    - "Notified new message: recipientId={}, messageId={}"
InMemoryNotifRepo     - "Notified new message: recipientId={}, messageId={}, conversationId={}"
```
