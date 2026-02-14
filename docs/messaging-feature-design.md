# 私信（DM）功能设计文档

**更新时间**: 2026-02-14
**状态**: 已实现 v2
**前置阅读**: [realtime-notification-design.md](./realtime-notification-design.md)

---

## 版本历史

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| v1 | 2026-02-11 | 基础私信：发送、对话列表、消息历史、已读、拉黑检查 |
| v2 | 2026-02-14 | DM权限落地、消息删除、消息撤回、消息回复、打字状态、在线状态 |

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
│    - Message (text + image + reply + delete + recall)       │
│    - ConversationDetail (aggregate)                         │
│                                                             │
│  Errors:                                                    │
│    - MessageError (sealed interface, 12 variants)           │
│                                                             │
│  Repository (Port):                                         │
│    - MessageRepository (10 methods)                         │
│                                                             │
│  Service (Port):                                            │
│    - PushNotificationService (FCM 预留)                     │
│                                                             │
│  Use Cases:                                                 │
│    - SendMessageUseCase      (含 DM 权限 + 回复)            │
│    - GetConversationsUseCase                                │
│    - GetMessagesUseCase                                     │
│    - MarkConversationReadUseCase                            │
│    - NotifyNewMessageUseCase (含撤回通知)                   │
│    - DeleteMessageUseCase    [NEW v2]                       │
│    - RecallMessageUseCase    [NEW v2]                       │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                        Data Layer                           │
│                 (Exposed ORM, PostgreSQL)                    │
├─────────────────────────────────────────────────────────────┤
│  Tables:                                                    │
│    - UsersTable (+dm_permission 列)                [MOD v2] │
│    - ConversationsTable                                     │
│    - MessagesTable (+reply_to_message_id,          [MOD v2] │
│                     +deleted_at, +recalled_at)              │
│                                                             │
│  Repository:                                                │
│    - ExposedMessageRepository                               │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   Infrastructure Layer                       │
├─────────────────────────────────────────────────────────────┤
│  - NoOpPushNotificationService (FCM stub)                   │
│  - InMemoryNotificationRepository                           │
│    (+notifyMessageRecalled, +notifyTypingIndicator, [MOD v2]│
│     +notifyUserPresenceChanged)                             │
│  - WebSocketConnectionManager                               │
│    (+isUserOnline, +getOnlineStatus)               [MOD v2] │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      Transport Layer                         │
│                 (REST + WebSocket)                           │
├─────────────────────────────────────────────────────────────┤
│  REST:                                                      │
│  GET    /v1/conversations                → 对话列表         │
│  POST   /v1/conversations/messages       → 发送消息(+回复)  │
│  GET    /v1/conversations/{id}/messages   → 消息历史         │
│  PUT    /v1/conversations/{id}/read      → 标记已读         │
│  DELETE /v1/messages/{id}                → 删除消息 [NEW v2]│
│  PUT    /v1/messages/{id}/recall         → 撤回消息 [NEW v2]│
│                                                             │
│  WebSocket 通知 (复用 /v1/notifications/ws):                │
│    ← "new_message"              推给接收者                  │
│    ← "messages_read"            推给发送者                  │
│    ← "message_recalled"         推给对方           [NEW v2] │
│    ← "typing_indicator"         推给对话伙伴       [NEW v2] │
│    ← "user_presence_changed"    广播               [NEW v2] │
│    → "typing" / "stop_typing"   客户端发送         [NEW v2] │
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

### 2. Single MessageRepository（而非拆分 Conversation + Message）

**原因**: 发送消息时需原子更新 `Conversation.lastMessageAt` + 插入 `Message`，同一事务边界。拆分 repo 会导致跨 repo 事务协调，增加复杂度。

### 3. 无独立 WebSocket 端点

**原因**: 私信通知复用现有 `/v1/notifications/ws` 通道，通过 `connectionManager.sendToUser()` 定向推送。避免客户端维护两条 WebSocket 连接。

消息类型通过 `type` 字段区分。

### 4. FCM 作为 Domain Port

```
domain/service/PushNotificationService.kt  (接口)
    ↓
infrastructure/service/NoOpPushNotificationService.kt  (当前：空实现)
infrastructure/service/FcmPushNotificationService.kt   (未来：FCM 实现)
```

### 5. DM Permission（v2 已落地）

`User` 模型新增 `dmPermission: DmPermission` 字段，持久化到 `users.dm_permission` 列（默认 `EVERYONE`）。

**实现位置**: `SendMessageUseCase` step 4

```kotlin
if (recipient.dmPermission == DmPermission.MUTUAL_FOLLOW) {
    val isMutualFollow = userRepository.isFollowing(cmd.senderId, cmd.recipientId)
        && userRepository.isFollowing(cmd.recipientId, cmd.senderId)
    ensure(isMutualFollow) { MessageError.DmPermissionDenied }
}
```

**查询成本**: 互关检查需 2 次 follows 表查询。仅当 recipient 设置了 `MUTUAL_FOLLOW` 时触发，默认 `EVERYONE` 跳过。

### 6. 消息软删除 vs 硬删除

**选择软删除**（`deleted_at` 字段）的原因：
- 审计追溯：被删除的消息仍可在后台查看
- 数据恢复：误删可恢复
- 客户端展示：显示「消息已删除」占位符，避免对话断层

**响应处理**: `Message.toResponse()` 检查 `isDeleted` / `isRecalled`，将 `content` 置空、`imageUrl` 置 null。客户端通过 `deletedAt` / `recalledAt` 字段判断状态。

### 7. 撤回 vs 删除的区别

| 维度 | 删除 (Delete) | 撤回 (Recall) |
|------|---------------|---------------|
| 操作者 | 消息发送者 | 消息发送者 |
| 时间限制 | 无 | 3 分钟内 |
| 对方可见 | 显示「消息已删除」 | 显示「消息已撤回」 |
| WebSocket 通知 | 无 | 推送 `message_recalled` 给对方 |
| 字段 | `deleted_at` | `recalled_at` |
| 语义 | 发送者不想看到 | 发送者想让双方都看不到 |

### 8. 打字状态：不持久化

打字状态是短暂事件（ephemeral event），只通过 WebSocket 实时转发，不写入数据库。

**流程**: 客户端发送 `{"type":"typing","conversationId":"xxx"}` → 服务端查 conversation 找到对方 → `sendToUser(partnerId)` 转发。

**性能考量**: 每次 typing 事件需查 conversation 表获取对方 ID。如果频率过高可在 WebSocket handler 层做客户端限流（建议客户端每 3 秒发一次 typing，停止输入 3 秒后发 stop_typing）。

### 9. 在线状态广播策略

**当前方案**: 用户连接/断开 WebSocket 时向所有在线用户广播 `user_presence_changed` 事件。

**仅当所有设备断开时才广播下线**: 一个用户可能有多个 WebSocket 连接（多设备），仅当 `connectionManager.isUserOnline(userId)` 返回 false 时才广播下线事件。

**未来优化**: 改为只推送给关注者或对话参与者，减少广播量。

---

## 数据模型

### 数据库表

```sql
-- 用户表 (仅展示 v2 新增列)
ALTER TABLE users ADD COLUMN dm_permission VARCHAR(20) DEFAULT 'EVERYONE';

-- 对话表 (无变化)
CREATE TABLE conversations (
    id              VARCHAR(36) PRIMARY KEY,
    participant1_id VARCHAR(36) REFERENCES users(id),
    participant2_id VARCHAR(36) REFERENCES users(id),
    last_message_id VARCHAR(36),
    last_message_at BIGINT,
    created_at      BIGINT,
    UNIQUE (participant1_id, participant2_id)
);

-- 消息表 (v2 新增 3 列)
CREATE TABLE messages (
    id                   VARCHAR(36) PRIMARY KEY,
    conversation_id      VARCHAR(36) REFERENCES conversations(id),
    sender_id            VARCHAR(36) REFERENCES users(id),
    content              VARCHAR(2000),
    image_url            VARCHAR(256),
    reply_to_message_id  VARCHAR(36),       -- [NEW v2] 引用的消息 ID
    read_at              BIGINT,            -- NULL = 未读
    deleted_at           BIGINT,            -- [NEW v2] NULL = 未删除
    recalled_at          BIGINT,            -- [NEW v2] NULL = 未撤回
    created_at           BIGINT
);
```

**列迁移方式**: Exposed 的 `SchemaUtils.addMissingColumnsStatements()` 在应用启动时自动添加新列，无需手动执行 DDL。

### Domain Models

```kotlin
// Value objects
@JvmInline value class ConversationId(val value: String)
@JvmInline value class MessageId(val value: String)
@JvmInline value class MessageContent private constructor(val value: String)  // max 2000

enum class DmPermission { EVERYONE, MUTUAL_FOLLOW }

// Entities
data class Conversation(id, participant1Id, participant2Id, lastMessageId?, lastMessageAt?, createdAt)

data class Message(
    id, conversationId, senderId, content, imageUrl?,
    replyToMessageId?,     // [NEW v2]
    readAt?,
    deletedAt?,            // [NEW v2]
    recalledAt?,           // [NEW v2]
    createdAt
) {
    val isDeleted: Boolean get() = deletedAt != null
    val isRecalled: Boolean get() = recalledAt != null
}

// User (新增字段)
data class User(
    ...,
    dmPermission: DmPermission = DmPermission.EVERYONE,  // [NEW v2]
    ...
)

// Aggregates
data class ConversationDetail(conversation, otherUser: User, lastMessage?, unreadCount: Int)
```

### Error Types

```kotlin
sealed interface MessageError {
    // v1 errors
    data object EmptyContent                             // 空内容
    data class ContentTooLong(actual, max)                // 超过 2000 字符
    data class ConversationNotFound(conversationId)       // 对话不存在
    data class MessageNotFound(messageId)                 // 消息不存在
    data class NotConversationParticipant(userId, convId) // 非参与者（403）
    data object CannotMessageSelf                         // 不能给自己发消息
    data class RecipientNotFound(recipientId)             // 接收者不存在
    data object DmPermissionDenied                        // DM 权限拒绝
    data class UserBlocked(userId)                        // 拉黑关系

    // v2 errors
    data object NotMessageSender                         // 非消息发送者
    data object RecallTimeExpired                        // 撤回超时（>3分钟）
    data object MessageAlreadyRecalled                   // 消息已被撤回
    data object MessageAlreadyDeleted                    // 消息已被删除
}
```

---

## Use Case 详解

### SendMessageUseCase（v2 更新）

**入口**: `POST /v1/conversations/messages`

**流程**:
1. 校验 `senderId != recipientId`
2. 校验 recipient 存在（`UserRepository.findById`）
3. 检查拉黑关系（双向）
4. **[NEW v2]** 检查 DM 权限：若 recipient.dmPermission == MUTUAL_FOLLOW，校验互关
5. 校验 content（`MessageContent()` 返回 Either）
6. `findOrCreateConversation(senderId, recipientId)` — canonical ordering
7. 插入 Message（含 `replyToMessageId`）+ 更新 Conversation.lastMessageAt（同一事务）
8. 返回 `SendMessageResult(message, conversation)`

**调用方额外操作（Route 层，异步）**:
- `appScope.launch { notifyNewMessageUseCase.execute(...) }`

### DeleteMessageUseCase [NEW v2]

**入口**: `DELETE /v1/messages/{id}`

**流程**:
1. 查询消息是否存在
2. 校验消息所属对话的参与者身份
3. 校验当前用户为消息发送者
4. 校验消息未被删除
5. 设置 `deleted_at = now()`

**约束**:
- 只有发送者可以删除自己的消息
- 已删除的消息不可重复删除
- 删除不通知对方（静默操作）

### RecallMessageUseCase [NEW v2]

**入口**: `PUT /v1/messages/{id}/recall`

**流程**:
1. 查询消息是否存在
2. 校验当前用户为消息发送者
3. 校验消息未被撤回或删除
4. 校验距发送时间 ≤ 3 分钟（`RECALL_TIME_LIMIT_MS = 180_000`）
5. 设置 `recalled_at = now()`

**调用方额外操作（Route 层，异步）**:
- `appScope.launch { notifyNewMessageUseCase.notifyMessageRecalled(messageId) }`
- 查找 conversation 的另一方，通过 WebSocket 推送 `message_recalled` 事件

### GetConversationsUseCase

**入口**: `GET /v1/conversations`

查询用户参与的所有对话，按 `lastMessageAt DESC` 排序。

**v2 变更**: 未读计数查询排除 `deleted_at IS NOT NULL` 和 `recalled_at IS NOT NULL` 的消息。

### GetMessagesUseCase

**入口**: `GET /v1/conversations/{id}/messages`

返回分页消息。已删除/已撤回的消息仍在结果中（客户端通过 `deletedAt`/`recalledAt` 字段判断显示方式），但 `content` 和 `imageUrl` 在 toResponse() 中被清空。

### MarkConversationReadUseCase

**入口**: `PUT /v1/conversations/{id}/read`

无变化。

### NotifyNewMessageUseCase（v2 扩展）

新增方法 `notifyMessageRecalled(messageId)`：
1. 查询 message → 获取 conversationId 和 senderId
2. 查询 conversation → 确定对方 userId
3. 构造 `NotificationEvent.MessageRecalled` 事件
4. 通过 `notificationRepository.notifyMessageRecalled(recipientId, event)` 推送

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

### message_recalled [NEW v2]（推给对话对方）

```json
{
  "type": "message_recalled",
  "data": {
    "messageId": "msg-uuid",
    "conversationId": "conv-uuid",
    "recalledByUserId": "sender-uuid",
    "timestamp": 1707600000000
  }
}
```

### typing_indicator [NEW v2]（推给对话伙伴）

```json
{
  "type": "typing_indicator",
  "data": {
    "conversationId": "conv-uuid",
    "userId": "user-uuid",
    "isTyping": true,
    "timestamp": 1707600000000
  }
}
```

### user_presence_changed [NEW v2]（广播给所有在线用户）

```json
{
  "type": "user_presence_changed",
  "data": {
    "userId": "user-uuid",
    "isOnline": true,
    "timestamp": 1707600000000
  }
}
```

### 客户端 → 服务端 WebSocket 消息 [NEW v2]

```json
// 开始输入
{"type": "typing", "conversationId": "conv-uuid"}

// 停止输入
{"type": "stop_typing", "conversationId": "conv-uuid"}

// 心跳 (已有)
{"type": "ping"}

// Post 订阅 (已有)
{"type": "subscribe_post", "postId": "post-uuid"}
{"type": "unsubscribe_post", "postId": "post-uuid"}
```

---

## 文件清单

### v1 新增文件

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

### v2 新增文件

| 路径 | 职责 |
|------|------|
| `domain/usecase/DeleteMessageUseCase.kt` | 消息软删除 |
| `domain/usecase/RecallMessageUseCase.kt` | 消息撤回（3分钟内） |

### v2 修改文件

| 路径 | 修改内容 |
|------|----------|
| `data/db/schema/UsersTable.kt` | 新增 `dm_permission` 列 |
| `data/db/schema/MessagingTable.kt` | MessagesTable 新增 `reply_to_message_id`, `deleted_at`, `recalled_at` 列 |
| `data/db/mapping/UserMapping.kt` | 映射 `dmPermission` 字段 |
| `data/db/mapping/MessageMapping.kt` | 映射新增的 3 个字段 |
| `data/repository/ExposedUserRepository.kt` | save() 中持久化 `dmPermission` |
| `data/repository/ExposedMessageRepository.kt` | 新增 `findMessageById`, `softDeleteMessage`, `recallMessage`；未读计数排除已删除/已撤回消息 |
| `domain/model/User.kt` | 新增 `dmPermission` 字段 |
| `domain/model/Conversation.kt` | Message 新增 `replyToMessageId`, `deletedAt`, `recalledAt` 字段 + computed properties |
| `domain/failure/MessageErrors.kt` | 新增 4 个错误变体 |
| `domain/repository/MessageRepository.kt` | 新增 3 个方法 |
| `domain/repository/NotificationRepository.kt` | 新增 `notifyMessageRecalled`, `notifyTypingIndicator`, `notifyUserPresenceChanged` |
| `domain/model/Notification.kt` | 新增 `MessageRecalled`, `TypingIndicator`, `UserPresenceChanged` 事件 |
| `domain/usecase/SendMessageUseCase.kt` | DM 权限检查 + replyToMessageId 传递 |
| `domain/usecase/NotifyNewMessageUseCase.kt` | 新增 `notifyMessageRecalled()` 方法；构造函数新增 `messageRepository` 依赖 |
| `infrastructure/repository/InMemoryNotificationRepository.kt` | 实现 3 个新通知方法 |
| `infrastructure/websocket/WebSocketConnectionManager.kt` | 新增 `isUserOnline()`, `getOnlineStatus()` |
| `features/notification/NotificationSchema.kt` | WebSocketClientMessageDto 新增 `conversationId` 字段 |
| `features/notification/NotificationWebSocket.kt` | 处理 typing/stop_typing；连接/断开时广播在线状态；注入 notificationRepository + messageRepository |
| `features/messaging/MessagingSchema.kt` | SendMessageRequest 新增 `replyToMessageId`；MessageResponse 新增 `replyToMessageId`, `deletedAt`, `recalledAt` |
| `features/messaging/MessagingMappers.kt` | toResponse() 处理删除/撤回内容清空；新增 4 个错误映射 |
| `features/messaging/MessagingRoutes.kt` | 新增 `DELETE /v1/messages/{id}` 和 `PUT /v1/messages/{id}/recall` 端点 |
| `core/di/MessagingModule.kt` | 注册 `DeleteMessageUseCase`, `RecallMessageUseCase`；`NotifyNewMessageUseCase` 新增第3个依赖 |
| `plugins/Routing.kt` | 注入并传递新 use cases + notificationRepository + messageRepository |

---

## 索引与查询性能

| 查询场景 | 使用索引 | 说明 |
|---------|----------|------|
| 查对话列表 (p1) | `idx_conversations_p1_last` | WHERE participant1_id = ? ORDER BY last_message_at DESC |
| 查对话列表 (p2) | `idx_conversations_p2_last` | WHERE participant2_id = ? ORDER BY last_message_at DESC |
| 对话去重 | `uk_conversation_participants` | UNIQUE(participant1_id, participant2_id) |
| 消息分页 | `idx_messages_conversation_created` | WHERE conversation_id = ? ORDER BY created_at DESC |
| 未读计数 | `idx_messages_conversation_read` | WHERE conversation_id = ? AND sender_id = ? AND read_at IS NULL |

**v2 性能影响**:
- 未读计数增加了 `AND deleted_at IS NULL AND recalled_at IS NULL` 过滤条件，走已有索引 + 行过滤
- typing 事件需要查 conversation 表（1 次主键查询），成本极低
- 在线状态广播是 `broadcastToAll`，连接数多时需考虑优化

---

## 调试指南

### 常见问题

**Q: 发消息返回 403 `DM_PERMISSION_DENIED`**
→ 对方设置了 `dmPermission = MUTUAL_FOLLOW`。检查双方是否互相关注（follows 表双向记录）。

**Q: 删除消息返回 403 `NOT_MESSAGE_SENDER`**
→ 只有消息发送者可以删除/撤回消息。检查当前用户是否为 message.sender_id。

**Q: 撤回消息返回 400 `RECALL_TIME_EXPIRED`**
→ 超过 3 分钟。检查 `System.currentTimeMillis() - message.createdAt > 180000`。

**Q: 对方收不到打字状态**
→ 1) 确认双方已连接 WebSocket
→ 2) 检查发送的 JSON 格式：`{"type":"typing","conversationId":"xxx"}`
→ 3) 确认 conversationId 有效且双方是该对话的参与者

**Q: 在线状态不更新**
→ 1) 确认目标用户的 WebSocket 连接正常
→ 2) 注意多设备场景：只有当所有设备都断开后才会广播下线
→ 3) 查看日志 "Broadcasted user presence change"

**Q: 消息历史中已删除/撤回的消息内容仍然显示**
→ 检查客户端是否根据 `deletedAt` / `recalledAt` 字段做了 UI 处理。服务端 API 返回的 `content` 已清空为 `""`。

### 关键日志点

```
SendMessageUseCase    - "Sending message: senderId={}, recipientId={}"
SendMessageUseCase    - "Message sent: messageId={}, conversationId={}"
ExposedMessageRepo    - "Created conversation: id={}, p1={}, p2={}"
ExposedMessageRepo    - "Saved message: id={}, conversationId={}"
ExposedMessageRepo    - "Marked {} messages as read: conversationId={}, userId={}"
NotifyNewMessageUC    - "Notified new message: recipientId={}, messageId={}"
NotifyNewMessageUC    - "Notified message recalled: recipientId={}, messageId={}"
InMemoryNotifRepo     - "Notified typing indicator: recipientId={}, conversationId={}"
InMemoryNotifRepo     - "Broadcasted user presence change: userId={}, isOnline={}"
NotificationWS        - "WebSocket connected/disconnected: userId={}"
```

---

## 未来扩展点

### 短期

- [ ] FCM 推送实现（替换 NoOpPushNotificationService）
- [ ] 对话静音（`muted_at` 字段，跳过推送）
- [ ] 在线状态优化：改为只推送给关注者/对话参与者
- [ ] 客户端打字事件限流（服务端 debounce）

### 中期

- [ ] 消息搜索（PostgreSQL FTS on messages.content）
- [ ] 图片消息缩略图（复用现有 Media 系统）
- [ ] 已读未读批量查询优化（Redis 缓存未读数）
- [ ] 在线状态 TTL（心跳超时自动标记下线）

### 长期

- [ ] 群聊（Conversation 模型扩展，`ConversationParticipantsTable`）
- [ ] 端到端加密（Signal Protocol）
- [ ] Redis 分布式推送（多实例部署）
- [ ] 消息反应（表情 reaction）
