# 私信（DM）客户端接入指南

**更新时间**: 2026-02-11
**适用**: Android (Kotlin) / iOS (Swift) / Web (TypeScript)
**前置阅读**: [auth-client-integration-guide.md](./auth-client-integration-guide.md)

---

## 1. 快速接入清单

- [ ] 所有请求带 `Authorization: Bearer <token>` 请求头
- [ ] 实现发送消息（`POST /v1/conversations/messages`）
- [ ] 实现对话列表（`GET /v1/conversations`），含下拉分页
- [ ] 实现消息历史（`GET /v1/conversations/{id}/messages`），含滚动分页
- [ ] 实现标记已读（`PUT /v1/conversations/{id}/read`），进入对话时调用
- [ ] WebSocket 监听 `new_message` 事件，实时显示新消息
- [ ] WebSocket 监听 `messages_read` 事件，更新已读状态

---

## 2. API 端点总览

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| `GET` | `/v1/conversations` | 对话列表 | 必须 |
| `POST` | `/v1/conversations/messages` | 发送消息 | 必须 |
| `GET` | `/v1/conversations/{id}/messages` | 消息历史 | 必须 |
| `PUT` | `/v1/conversations/{id}/read` | 标记已读 | 必须 |

所有端点要求 JWT 认证，未携带或过期返回 `401`。

---

## 3. 发送消息

### 请求

```
POST /v1/conversations/messages
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "recipientId": "user-uuid-of-recipient",
  "content": "你好！",
  "imageUrl": "https://example.com/photo.jpg"   // 可选
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `recipientId` | string | 是 | 接收者的 UserId |
| `content` | string | 是 | 消息文本，1~2000 字符 |
| `imageUrl` | string | 否 | 图片 URL（复用 Media 上传系统） |

### 成功响应 `201 Created`

```json
{
  "id": "msg-uuid",
  "conversationId": "conv-uuid",
  "senderId": "your-user-id",
  "content": "你好！",
  "imageUrl": "https://example.com/photo.jpg",
  "readAt": null,
  "createdAt": 1707600000000
}
```

### 错误响应

| HTTP 状态 | code | 场景 |
|-----------|------|------|
| `400` | `EMPTY_CONTENT` | 消息内容为空 |
| `400` | `CONTENT_TOO_LONG` | 超过 2000 字符 |
| `400` | `CANNOT_MESSAGE_SELF` | recipientId 等于自己 |
| `404` | `RECIPIENT_NOT_FOUND` | 接收者不存在 |
| `403` | `DM_PERMISSION_DENIED` | 对方仅允许互关用户发消息（未来） |

### 示例代码

```kotlin
// Android - Ktor Client
suspend fun sendMessage(recipientId: String, content: String, imageUrl: String? = null): MessageResponse {
    return httpClient.post("$BASE_URL/v1/conversations/messages") {
        contentType(ContentType.Application.Json)
        setBody(SendMessageRequest(recipientId, content, imageUrl))
    }.body()
}

@Serializable
data class SendMessageRequest(
    val recipientId: String,
    val content: String,
    val imageUrl: String? = null
)
```

```typescript
// Web - TypeScript
async function sendMessage(recipientId: string, content: string, imageUrl?: string) {
  const res = await fetch(`${BASE_URL}/v1/conversations/messages`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ recipientId, content, imageUrl })
  });

  if (!res.ok) {
    const error = await res.json();
    throw new ApiError(error.code, error.message);
  }
  return res.json();
}
```

### 发送图片消息

图片消息需要两步：先上传图片，再发送消息。

```kotlin
// 1. 上传图片（复用现有 Media 端点）
val uploadResult = httpClient.submitFormWithBinaryData(
    url = "$BASE_URL/v1/media/upload",
    formData = formData {
        append("file", imageBytes, Headers.build {
            append(HttpHeaders.ContentDisposition, "filename=\"photo.jpg\"")
            append(HttpHeaders.ContentType, "image/jpeg")
        })
    }
).body<MediaUploadResponse>()

// 2. 发送带图片 URL 的消息
val message = sendMessage(
    recipientId = recipientId,
    content = "看看这张照片",
    imageUrl = uploadResult.url
)
```

---

## 4. 对话列表

### 请求

```
GET /v1/conversations?limit=20&offset=0
Authorization: Bearer <token>
```

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `limit` | int | 20 | 每页条数 |
| `offset` | int | 0 | 跳过条数 |

### 成功响应 `200 OK`

```json
{
  "conversations": [
    {
      "id": "conv-uuid",
      "otherUser": {
        "id": "user-uuid",
        "displayName": "Alice",
        "username": "alice",
        "avatarUrl": "https://example.com/avatar.jpg"
      },
      "lastMessage": {
        "id": "msg-uuid",
        "conversationId": "conv-uuid",
        "senderId": "user-uuid",
        "content": "See you tomorrow!",
        "imageUrl": null,
        "readAt": null,
        "createdAt": 1707600000000
      },
      "unreadCount": 3,
      "createdAt": 1707500000000
    }
  ],
  "hasMore": true
}
```

**关键字段说明**:
- `otherUser`: 对话中对方的用户信息（不是你自己）
- `lastMessage`: 最后一条消息的完整内容，用于列表预览；如果对话刚创建还没消息，为 `null`
- `unreadCount`: 对方发送的、你尚未阅读的消息数量
- `hasMore`: 是否还有更多对话可加载

### 分页加载

```kotlin
// Android - 下拉加载更多
class ConversationListViewModel : ViewModel() {
    private var offset = 0
    private val limit = 20
    private var hasMore = true

    val conversations = mutableStateListOf<ConversationResponse>()

    fun loadMore() {
        if (!hasMore) return
        viewModelScope.launch {
            val response = api.getConversations(limit, offset)
            conversations.addAll(response.conversations)
            hasMore = response.hasMore
            offset += response.conversations.size
        }
    }
}
```

---

## 5. 消息历史

### 请求

```
GET /v1/conversations/{conversationId}/messages?limit=50&offset=0
Authorization: Bearer <token>
```

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `limit` | int | 50 | 每页条数 |
| `offset` | int | 0 | 跳过条数 |

### 成功响应 `200 OK`

```json
{
  "messages": [
    {
      "id": "msg-uuid-2",
      "conversationId": "conv-uuid",
      "senderId": "other-user-id",
      "content": "See you tomorrow!",
      "imageUrl": null,
      "readAt": 1707600050000,
      "createdAt": 1707600000000
    },
    {
      "id": "msg-uuid-1",
      "conversationId": "conv-uuid",
      "senderId": "your-user-id",
      "content": "Sounds good",
      "imageUrl": null,
      "readAt": 1707599900000,
      "createdAt": 1707599800000
    }
  ],
  "hasMore": true
}
```

**注意**: 消息按 `createdAt DESC` 排序（最新在前）。客户端显示时需要反转，或使用 `reversed()` 在 UI 层处理。

### 错误响应

| HTTP 状态 | code | 场景 |
|-----------|------|------|
| `404` | `CONVERSATION_NOT_FOUND` | 对话 ID 不存在 |
| `403` | `NOT_PARTICIPANT` | 你不是该对话的参与者 |

### 滚动分页（向上加载更多历史消息）

```kotlin
// Android - LazyColumn 向上滚动加载
class ChatViewModel(private val conversationId: String) : ViewModel() {
    private var offset = 0
    private val limit = 50
    private var hasMore = true

    val messages = mutableStateListOf<MessageResponse>()

    fun loadInitial() {
        viewModelScope.launch {
            val response = api.getMessages(conversationId, limit, 0)
            messages.addAll(response.messages.reversed())  // 反转：旧→新
            hasMore = response.hasMore
            offset = response.messages.size
        }
    }

    fun loadOlderMessages() {
        if (!hasMore) return
        viewModelScope.launch {
            val response = api.getMessages(conversationId, limit, offset)
            messages.addAll(0, response.messages.reversed())  // 插入顶部
            hasMore = response.hasMore
            offset += response.messages.size
        }
    }
}
```

---

## 6. 标记已读

进入对话详情页时调用此端点，将对话中所有未读消息标为已读。

### 请求

```
PUT /v1/conversations/{conversationId}/read
Authorization: Bearer <token>
```

### 成功响应 `200 OK`

```json
{
  "conversationId": "conv-uuid",
  "readAt": 1707600100000
}
```

### 调用时机

```kotlin
// Android - 进入聊天页面时
LaunchedEffect(conversationId) {
    // 标记已读
    api.markAsRead(conversationId)
    // 加载消息
    viewModel.loadInitial()
}
```

```typescript
// Web - 打开对话时
useEffect(() => {
  markConversationAsRead(conversationId);
  loadMessages(conversationId);
}, [conversationId]);
```

---

## 7. 实时通知（WebSocket）

私信通知通过已有的 WebSocket 通道推送，无需建立新连接。

### 7.1 连接方式

参考 [realtime-notification-client-examples.md](./realtime-notification-client-examples.md) 中的 WebSocket 连接方式：

```
ws://host:port/v1/notifications/ws
Authorization: Bearer <token>
```

### 7.2 新消息通知

当有人给你发私信时，WebSocket 会收到：

```json
{
  "type": "new_message",
  "data": {
    "messageId": "msg-uuid",
    "conversationId": "conv-uuid",
    "senderDisplayName": "Alice",
    "senderUsername": "alice",
    "contentPreview": "Hey, how's it going?",
    "timestamp": 1707600000000
  }
}
```

### 7.3 已读回执通知

当对方读了你的消息时，WebSocket 会收到：

```json
{
  "type": "messages_read",
  "data": {
    "conversationId": "conv-uuid",
    "readByUserId": "other-user-id",
    "timestamp": 1707600100000
  }
}
```

### 7.4 客户端处理

```kotlin
// Android - WebSocket 消息处理
override fun onMessage(webSocket: WebSocket, text: String) {
    val message = json.decodeFromString<WebSocketMessage>(text)

    when (message.type) {
        "new_message" -> {
            val data = json.decodeFromString<NewMessageEvent>(message.dataJson)

            // 1. 如果当前在对话列表页 → 更新列表（置顶 + 更新预览 + 未读数+1）
            conversationListState.updateConversation(data.conversationId, data)

            // 2. 如果当前在该对话的聊天页 → 追加消息到底部 + 自动标记已读
            if (currentConversationId == data.conversationId) {
                chatState.appendMessage(data)
                api.markAsRead(data.conversationId)  // 自动标记已读
            }

            // 3. 如果在其他页面 → 显示通知横幅 / 更新未读 badge
            showNotificationBadge(data.senderDisplayName, data.contentPreview)
        }

        "messages_read" -> {
            val data = json.decodeFromString<MessagesReadEvent>(message.dataJson)

            // 更新消息的已读状态（双勾 ✓✓）
            chatState.markMessagesAsRead(data.conversationId, data.timestamp)
        }

        // ... 其他通知类型 (new_post, post_liked, etc.)
    }
}
```

```typescript
// Web - TypeScript
ws.onmessage = (event) => {
  const message = JSON.parse(event.data);

  switch (message.type) {
    case 'new_message':
      handleNewMessage(message.data);
      break;

    case 'messages_read':
      handleMessagesRead(message.data);
      break;
  }
};

function handleNewMessage(data: NewMessageEvent) {
  // 更新对话列表
  updateConversationList(data.conversationId, {
    lastMessagePreview: data.contentPreview,
    senderName: data.senderDisplayName,
    timestamp: data.timestamp
  });

  // 如果在当前对话中，追加消息
  if (currentConversation === data.conversationId) {
    appendMessageToChat(data);
    markAsRead(data.conversationId);
  } else {
    incrementUnreadBadge();
  }
}

function handleMessagesRead(data: MessagesReadEvent) {
  // 更新该对话中所有你发送的消息为已读
  markSentMessagesAsRead(data.conversationId, data.timestamp);
}
```

---

## 8. TypeScript 类型定义

```typescript
// ========== Request ==========
interface SendMessageRequest {
  recipientId: string;
  content: string;
  imageUrl?: string;
}

// ========== Response ==========
interface MessageResponse {
  id: string;
  conversationId: string;
  senderId: string;
  content: string;
  imageUrl: string | null;
  readAt: number | null;       // null = 未读, 数字 = 已读时间戳
  createdAt: number;
}

interface ConversationUserDto {
  id: string;
  displayName: string;
  username: string;
  avatarUrl: string | null;
}

interface ConversationResponse {
  id: string;
  otherUser: ConversationUserDto;
  lastMessage: MessageResponse | null;
  unreadCount: number;
  createdAt: number;
}

interface ConversationListResponse {
  conversations: ConversationResponse[];
  hasMore: boolean;
}

interface MessageListResponse {
  messages: MessageResponse[];
  hasMore: boolean;
}

interface MarkReadResponse {
  conversationId: string;
  readAt: number;
}

// ========== WebSocket Events ==========
interface NewMessageEvent {
  messageId: string;
  conversationId: string;
  senderDisplayName: string;
  senderUsername: string;
  contentPreview: string;
  timestamp: number;
}

interface MessagesReadEvent {
  conversationId: string;
  readByUserId: string;
  timestamp: number;
}
```

---

## 9. Kotlin 数据类（Android / KMP）

```kotlin
@Serializable
data class SendMessageRequest(
    val recipientId: String,
    val content: String,
    val imageUrl: String? = null
)

@Serializable
data class MessageResponse(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val content: String,
    val imageUrl: String?,
    val readAt: Long?,
    val createdAt: Long
)

@Serializable
data class ConversationUserDto(
    val id: String,
    val displayName: String,
    val username: String,
    val avatarUrl: String?
)

@Serializable
data class ConversationResponse(
    val id: String,
    val otherUser: ConversationUserDto,
    val lastMessage: MessageResponse?,
    val unreadCount: Int,
    val createdAt: Long
)

@Serializable
data class ConversationListResponse(
    val conversations: List<ConversationResponse>,
    val hasMore: Boolean
)

@Serializable
data class MessageListResponse(
    val messages: List<MessageResponse>,
    val hasMore: Boolean
)

@Serializable
data class MarkReadResponse(
    val conversationId: String,
    val readAt: Long
)
```

---

## 10. 完整交互流程

### 10.1 首次发起对话

```
用户 A 给用户 B 发消息（A 和 B 之前没聊过）

A: POST /v1/conversations/messages
   { "recipientId": "B-id", "content": "Hi!" }

Server:
  1. 创建 Conversation (A, B)     ← 自动
  2. 保存 Message                 ← 自动
  3. 推送 WebSocket "new_message" 给 B  ← 异步

A ← 201 { id: "msg-1", conversationId: "conv-1", ... }
B ← WS  { type: "new_message", data: { messageId: "msg-1", ... } }
```

### 10.2 继续对话

```
B 回复 A

B: POST /v1/conversations/messages
   { "recipientId": "A-id", "content": "Hey!" }

Server:
  1. 找到已有的 Conversation      ← 自动
  2. 保存 Message
  3. 推送给 A

A ← WS { type: "new_message", data: { ... } }
```

### 10.3 查看对话 + 已读

```
A 打开和 B 的对话

A: GET /v1/conversations/conv-1/messages?limit=50
A: PUT /v1/conversations/conv-1/read

Server:
  1. 返回消息历史
  2. 标记 B 发送的未读消息为已读
  3. （未来）推送 "messages_read" 给 B
```

---

## 11. 错误码汇总

| HTTP 状态 | code | message | 场景 |
|-----------|------|---------|------|
| `400` | `EMPTY_CONTENT` | 消息内容不能为空 | content 为空或空白 |
| `400` | `CONTENT_TOO_LONG` | 消息内容过长：最多 2000 字符 | content > 2000 字符 |
| `400` | `CANNOT_MESSAGE_SELF` | 不能给自己发送消息 | recipientId = 自己 |
| `400` | `MISSING_PARAM` | 缺少参数 | URL 参数缺失 |
| `401` | `UNAUTHORIZED` | 未授权访问 | 未携带或无效 JWT |
| `403` | `NOT_PARTICIPANT` | 您不是该对话的参与者 | 试图查看他人对话 |
| `403` | `DM_PERMISSION_DENIED` | 对方仅允许互关用户发消息 | 对方开启了互关限制（未来）|
| `404` | `RECIPIENT_NOT_FOUND` | 接收者不存在 | recipientId 无效 |
| `404` | `CONVERSATION_NOT_FOUND` | 对话不存在 | conversationId 无效 |

错误响应格式统一为：

```json
{
  "code": "ERROR_CODE",
  "message": "人类可读的错误描述",
  "timestamp": 1707600000000
}
```

---

## 12. 测试检查清单

### 基础流程

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 1 | A 发消息给 B（首次） | 201，自动创建对话 |
| 2 | B 发消息给 A | 201，复用同一个对话 |
| 3 | A 查对话列表 | 包含与 B 的对话，unreadCount = 1 |
| 4 | A 查消息历史 | 返回所有消息，按时间倒序 |
| 5 | A 标记已读 | B 的消息 readAt 被设置 |
| 6 | A 再查对话列表 | unreadCount = 0 |

### 边界和错误

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 7 | 发空消息 | 400 `EMPTY_CONTENT` |
| 8 | 发 2001 字符消息 | 400 `CONTENT_TOO_LONG` |
| 9 | 发消息给自己 | 400 `CANNOT_MESSAGE_SELF` |
| 10 | 发消息给不存在的用户 | 404 `RECIPIENT_NOT_FOUND` |
| 11 | 查看不存在的对话消息 | 404 `CONVERSATION_NOT_FOUND` |
| 12 | 查看他人对话的消息 | 403 `NOT_PARTICIPANT` |

### 实时通知

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 13 | A 发消息，B 在线 | B 的 WebSocket 收到 `new_message` |
| 14 | A 发消息，B 离线 | 无 WebSocket 推送（消息已持久化，B 上线后从 API 获取） |
| 15 | A 标记已读 | （未来）B 的 WebSocket 收到 `messages_read` |
