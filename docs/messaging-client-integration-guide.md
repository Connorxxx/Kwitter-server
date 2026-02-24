# 私信（DM）客户端接入指南

**更新时间**: 2026-02-14
**版本**: v2
**适用**: Android (Kotlin) / iOS (Swift) / Web (TypeScript)
**前置阅读**: [auth-client-integration-guide.md](./auth-client-integration-guide.md)

---

## 1. 快速接入清单

### 基础功能（v1）

- [ ] 所有请求带 `Authorization: Bearer <token>` 请求头
- [ ] 实现发送消息（`POST /v1/conversations/messages`）
- [ ] 实现对话列表（`GET /v1/conversations`），含下拉分页
- [ ] 实现消息历史（`GET /v1/conversations/{id}/messages`），含滚动分页
- [ ] 实现标记已读（`PUT /v1/conversations/{id}/read`），进入对话时调用
- [ ] WebSocket 监听 `new_message` 事件，实时显示新消息
- [ ] WebSocket 监听 `messages_read` 事件，更新已读状态

### 新增功能（v2）

- [ ] 发送消息支持回复/引用（`replyToMessageId` 字段）
- [ ] 实现消息删除（`DELETE /v1/messages/{id}`）
- [ ] 实现消息撤回（`PUT /v1/messages/{id}/recall`），含 3 分钟倒计时 UI
- [ ] WebSocket 监听 `message_recalled` 事件，实时更新 UI
- [ ] WebSocket 发送 `typing` / `stop_typing` 事件
- [ ] WebSocket 监听 `typing_indicator` 事件，显示打字状态
- [ ] WebSocket 监听 `user_presence_changed` 事件，显示在线状态
- [ ] 处理 `MessageResponse` 中的 `deletedAt` / `recalledAt` 字段（内容占位显示）

---

## 2. API 端点总览

| 方法 | 路径 | 说明 | 版本 |
|------|------|------|------|
| `GET` | `/v1/conversations` | 对话列表 | v1 |
| `POST` | `/v1/conversations/messages` | 发送消息（含回复） | v1, v2 扩展 |
| `GET` | `/v1/conversations/{id}/messages` | 消息历史 | v1 |
| `PUT` | `/v1/conversations/{id}/read` | 标记已读 | v1 |
| `DELETE` | `/v1/messages/{id}` | 删除消息 | v2 |
| `PUT` | `/v1/messages/{id}/recall` | 撤回消息 | v2 |

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
  "imageUrl": "https://example.com/photo.jpg",
  "replyToMessageId": "msg-uuid-to-reply"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `recipientId` | string | 是 | 接收者的 UserId |
| `content` | string | 是 | 消息文本，1~2000 字符 |
| `imageUrl` | string | 否 | 图片 URL（复用 Media 上传系统） |
| `replyToMessageId` | string | 否 | **[v2]** 被回复/引用的消息 ID |

### 成功响应 `201 Created`

```json
{
  "id": "msg-uuid",
  "conversationId": "conv-uuid",
  "senderId": "your-user-id",
  "content": "你好！",
  "imageUrl": "https://example.com/photo.jpg",
  "replyToMessageId": "msg-uuid-to-reply",
  "readAt": null,
  "deletedAt": null,
  "recalledAt": null,
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
| `403` | `DM_PERMISSION_DENIED` | 对方仅允许互关用户发消息 |
| `403` | `USER_BLOCKED` | 存在拉黑关系，无法发送消息 |

### 示例代码

```kotlin
// Android - Ktor Client
suspend fun sendMessage(
    recipientId: String,
    content: String,
    imageUrl: String? = null,
    replyToMessageId: String? = null
): MessageResponse {
    return httpClient.post("$BASE_URL/v1/conversations/messages") {
        contentType(ContentType.Application.Json)
        setBody(SendMessageRequest(recipientId, content, imageUrl, replyToMessageId))
    }.body()
}

@Serializable
data class SendMessageRequest(
    val recipientId: String,
    val content: String,
    val imageUrl: String? = null,
    val replyToMessageId: String? = null
)
```

```typescript
// Web - TypeScript
async function sendMessage(
  recipientId: string,
  content: string,
  imageUrl?: string,
  replyToMessageId?: string
) {
  const res = await fetch(`${BASE_URL}/v1/conversations/messages`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ recipientId, content, imageUrl, replyToMessageId })
  });

  if (!res.ok) {
    const error = await res.json();
    throw new ApiError(error.code, error.message);
  }
  return res.json();
}
```

### 回复消息 [v2]

回复消息时，传入被回复消息的 ID。客户端应在 UI 上展示被引用的原始消息内容。

```kotlin
// 回复某条消息
val reply = sendMessage(
    recipientId = otherUserId,
    content = "同意你说的！",
    replyToMessageId = originalMessage.id
)
```

**客户端职责**:
- 本地缓存消息列表，用 `replyToMessageId` 查找原始消息内容展示
- 如果原始消息不在本地缓存中，可显示「引用的消息」占位符
- 原始消息被删除/撤回时，引用区域显示「消息已删除」或「消息已撤回」

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
| `limit` | int | 20 | 每页条数（1~100） |
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
        "replyToMessageId": null,
        "readAt": null,
        "deletedAt": null,
        "recalledAt": null,
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
- `lastMessage.deletedAt` / `lastMessage.recalledAt`: 非 null 时，`content` 为空字符串，客户端应显示「消息已删除」或「消息已撤回」
- `unreadCount`: 对方发送的、你尚未阅读的消息数量（排除已删除和已撤回的消息）
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
| `limit` | int | 50 | 每页条数（1~100） |
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
      "replyToMessageId": null,
      "readAt": 1707600050000,
      "deletedAt": null,
      "recalledAt": null,
      "createdAt": 1707600000000
    },
    {
      "id": "msg-uuid-1",
      "conversationId": "conv-uuid",
      "senderId": "your-user-id",
      "content": "",
      "imageUrl": null,
      "replyToMessageId": null,
      "readAt": 1707599900000,
      "deletedAt": null,
      "recalledAt": 1707599850000,
      "createdAt": 1707599800000
    }
  ],
  "hasMore": true
}
```

**注意**:
- 消息按 `createdAt DESC` 排序（最新在前）。客户端显示时需要反转。
- 已删除/已撤回的消息**仍然返回**（保证列表连续性），但 `content` 为空字符串，`imageUrl` 为 null。
- 客户端根据 `deletedAt` / `recalledAt` 是否为 null 决定显示占位符。

### 消息状态判断逻辑

```kotlin
// 客户端渲染消息的判断逻辑
fun renderMessage(msg: MessageResponse) {
    when {
        msg.recalledAt != null -> showRecalledPlaceholder("消息已撤回")
        msg.deletedAt != null  -> showDeletedPlaceholder("消息已删除")
        else                   -> showNormalMessage(msg.content, msg.imageUrl)
    }

    // 如果是回复消息，显示引用区域
    if (msg.replyToMessageId != null) {
        val originalMsg = findMessageInCache(msg.replyToMessageId)
        showReplyQuote(originalMsg)
    }
}
```

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

## 7. 删除消息 [v2]

删除自己发送的消息（逻辑删除）。删除后消息仍存在于消息列表中，但 content 被清空，客户端显示「消息已删除」。

### 请求

```
DELETE /v1/messages/{messageId}
Authorization: Bearer <token>
```

### 成功响应 `204 No Content`

（无响应体）

### 错误响应

| HTTP 状态 | code | 场景 |
|-----------|------|------|
| `404` | `MESSAGE_NOT_FOUND` | 消息不存在 |
| `403` | `NOT_MESSAGE_SENDER` | 不是消息发送者 |
| `403` | `NOT_PARTICIPANT` | 不是对话参与者 |
| `409` | `MESSAGE_ALREADY_DELETED` | 消息已被删除 |

### 示例代码

```kotlin
// Android
suspend fun deleteMessage(messageId: String) {
    httpClient.delete("$BASE_URL/v1/messages/$messageId")
}

// 调用后更新本地 UI
fun onDeleteMessage(messageId: String) {
    viewModelScope.launch {
        api.deleteMessage(messageId)
        // 更新本地消息状态
        messages.replaceAll { msg ->
            if (msg.id == messageId) msg.copy(
                content = "",
                imageUrl = null,
                deletedAt = System.currentTimeMillis()
            ) else msg
        }
    }
}
```

```typescript
// Web
async function deleteMessage(messageId: string) {
  await fetch(`${BASE_URL}/v1/messages/${messageId}`, {
    method: 'DELETE',
    headers: { 'Authorization': `Bearer ${token}` }
  });

  // 更新本地状态
  updateMessage(messageId, {
    content: '',
    imageUrl: null,
    deletedAt: Date.now()
  });
}
```

### 注意事项

- 删除是**单向操作**，不通知对方（静默删除）
- 对方的消息列表中该消息仍然正常显示原始内容
- 如果你同时想让对方也看不到消息内容，应使用**撤回**功能（有 3 分钟时间限制）

---

## 8. 撤回消息 [v2]

撤回自己发送的消息，仅限发送后 3 分钟内。撤回后双方都无法看到原始内容，并且对方会收到 WebSocket 实时通知。

### 请求

```
PUT /v1/messages/{messageId}/recall
Authorization: Bearer <token>
```

### 成功响应 `200 OK`

```json
{
  "messageId": "msg-uuid",
  "recalled": true
}
```

### 错误响应

| HTTP 状态 | code | 场景 |
|-----------|------|------|
| `404` | `MESSAGE_NOT_FOUND` | 消息不存在 |
| `403` | `NOT_MESSAGE_SENDER` | 不是消息发送者 |
| `400` | `RECALL_TIME_EXPIRED` | 超过 3 分钟撤回时限 |
| `409` | `MESSAGE_ALREADY_RECALLED` | 消息已被撤回 |
| `409` | `MESSAGE_ALREADY_DELETED` | 消息已被删除 |

### 示例代码

```kotlin
// Android
suspend fun recallMessage(messageId: String) {
    httpClient.put("$BASE_URL/v1/messages/$messageId/recall")
}

// UI: 长按消息 → 弹出菜单 → 撤回
fun onRecallMessage(message: MessageResponse) {
    val elapsedMs = System.currentTimeMillis() - message.createdAt
    val threeMinutesMs = 3 * 60 * 1000L

    if (elapsedMs > threeMinutesMs) {
        showToast("已超过 3 分钟，无法撤回")
        return
    }

    viewModelScope.launch {
        try {
            api.recallMessage(message.id)
            // 更新本地消息状态
            messages.replaceAll { msg ->
                if (msg.id == message.id) msg.copy(
                    content = "",
                    imageUrl = null,
                    recalledAt = System.currentTimeMillis()
                ) else msg
            }
        } catch (e: ApiException) {
            when (e.code) {
                "RECALL_TIME_EXPIRED" -> showToast("已超过 3 分钟，无法撤回")
                "MESSAGE_ALREADY_RECALLED" -> showToast("消息已被撤回")
                else -> showToast("撤回失败")
            }
        }
    }
}
```

```typescript
// Web
async function recallMessage(messageId: string) {
  const res = await fetch(`${BASE_URL}/v1/messages/${messageId}/recall`, {
    method: 'PUT',
    headers: { 'Authorization': `Bearer ${token}` }
  });

  if (!res.ok) {
    const error = await res.json();
    throw new ApiError(error.code, error.message);
  }
  return res.json();
}
```

### 撤回 UI 建议

- 只对自己发送的消息显示「撤回」选项
- 发送超过 3 分钟的消息，UI 上隐藏「撤回」按钮（或灰置）
- 可选：显示撤回倒计时（3:00 → 0:00）

---

## 9. 实时通知（WebSocket）

私信通知通过已有的 WebSocket 通道推送，无需建立新连接。

### 9.1 连接方式

```
ws://host:port/v1/notifications/ws
Authorization: Bearer <token>
```

### 9.2 新消息通知

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

### 9.3 已读回执通知

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

### 9.4 消息撤回通知 [v2]

当对方撤回了一条消息时，WebSocket 会收到：

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

**客户端处理**: 收到此事件后，在本地消息列表中将对应消息标记为已撤回，将 content 清空并显示「对方撤回了一条消息」。

### 9.5 打字状态指示器 [v2]

#### 发送打字状态

客户端在用户输入时，通过 WebSocket 发送打字事件：

```json
// 用户开始输入
{"type": "typing", "conversationId": "conv-uuid"}

// 用户停止输入
{"type": "stop_typing", "conversationId": "conv-uuid"}
```

#### 接收打字状态

当对方正在输入时，WebSocket 会收到：

```json
{
  "type": "typing_indicator",
  "data": {
    "conversationId": "conv-uuid",
    "userId": "other-user-id",
    "isTyping": true,
    "timestamp": 1707600000000
  }
}
```

#### 打字状态实现建议

```kotlin
// Android - 发送打字状态（带 debounce）
class ChatViewModel(private val conversationId: String) : ViewModel() {
    private var typingJob: Job? = null
    private var isCurrentlyTyping = false

    // 输入框文字变化时调用
    fun onTextChanged(text: String) {
        if (text.isNotEmpty() && !isCurrentlyTyping) {
            isCurrentlyTyping = true
            webSocket.send("""{"type":"typing","conversationId":"$conversationId"}""")
        }

        // 重置停止输入计时器
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(3000) // 3 秒无输入 → 发送 stop_typing
            if (isCurrentlyTyping) {
                isCurrentlyTyping = false
                webSocket.send("""{"type":"stop_typing","conversationId":"$conversationId"}""")
            }
        }
    }

    // 发送消息后立即停止打字状态
    fun onMessageSent() {
        typingJob?.cancel()
        if (isCurrentlyTyping) {
            isCurrentlyTyping = false
            webSocket.send("""{"type":"stop_typing","conversationId":"$conversationId"}""")
        }
    }
}
```

```typescript
// Web - 打字状态 debounce
let typingTimer: NodeJS.Timeout | null = null;
let isTyping = false;

function onInputChange(text: string, conversationId: string) {
  if (text.length > 0 && !isTyping) {
    isTyping = true;
    ws.send(JSON.stringify({ type: 'typing', conversationId }));
  }

  // 重置计时器
  if (typingTimer) clearTimeout(typingTimer);
  typingTimer = setTimeout(() => {
    if (isTyping) {
      isTyping = false;
      ws.send(JSON.stringify({ type: 'stop_typing', conversationId }));
    }
  }, 3000);
}
```

#### 显示打字状态

```kotlin
// Android - Compose UI
@Composable
fun TypingIndicator(isOtherUserTyping: Boolean) {
    AnimatedVisibility(visible = isOtherUserTyping) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 三个跳动的圆点动画
            TypingDots()
            Spacer(modifier = Modifier.width(8.dp))
            Text("对方正在输入...", style = MaterialTheme.typography.bodySmall)
        }
    }
}
```

**注意**: 收到 `typing_indicator` 后，客户端应启动一个超时计时器（如 5 秒）。如果 5 秒内没有再次收到 `typing_indicator(isTyping=true)`，自动隐藏打字状态。这样即使 `stop_typing` 消息丢失也不会一直显示打字中。

### 9.6 在线状态 [v3]

#### 连接时序

WebSocket 建连成功后，服务端按以下顺序下发事件：

```
1. connected                               ← 连接确认
2. presence_snapshot                        ← 对话对端在线状态快照（保证必发，可为空）
3. user_presence_changed(isOnline=true)     ← 仅首次上线时推送给对话对端
```

客户端应先用快照初始化在线状态 Map，再用后续增量事件覆盖。

#### 服务端保证（协议契约）

1. **每次连接必发 `presence_snapshot`**，即使 `users=[]`（无对话或查询降级）。
2. `presence_snapshot` 不会缺失 — 客户端可以将其作为"初始化完成"的信号。
3. 快照之后才会收到增量 `user_presence_changed` 事件。
4. `user_presence_changed` 仅推送给对话对端，不广播给无关用户。
5. 多设备场景：仅首个会话建立时广播上线（0→1），仅最后一个会话断开时广播下线（1→0）。

#### 接收在线状态快照

连接成功后，服务端会**单播**一个 `presence_snapshot` 给当前用户，包含该用户所有会话对端的在线状态：

```json
{
  "type": "presence_snapshot",
  "data": {
    "users": [
      { "userId": "user-uuid-1", "isOnline": true, "timestamp": 1707600000000 },
      { "userId": "user-uuid-2", "isOnline": false, "timestamp": 1707600000000 }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `data.users` | `Array` | 当前用户所有会话对端的在线状态列表（可为空数组） |
| `data.users[].userId` | `String` | 对端用户 ID |
| `data.users[].isOnline` | `Boolean` | 是否在线 |
| `data.users[].timestamp` | `Long` | 服务端生成快照的时间戳（ms） |

**客户端处理**：收到后清空旧的在线状态 Map，再遍历 `data.users` 批量写入。

#### 接收在线状态变更（增量）

当对话对端上线或下线时，WebSocket 会收到：

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

**v3 变更**：此事件不再广播给所有在线用户，仅推送给对话对端。线上格式不变，客户端无需修改解析逻辑。

#### 在线状态实现建议

```kotlin
// Android - 维护在线用户集合
class PresenceManager {
    private val _onlineUsers = mutableStateMapOf<String, Boolean>()
    val onlineUsers: Map<String, Boolean> = _onlineUsers

    // 快照：清空旧状态，批量初始化（每次重连都会触发）
    fun handlePresenceSnapshot(users: List<PresenceUser>) {
        _onlineUsers.clear()
        users.forEach { _onlineUsers[it.userId] = it.isOnline }
    }

    // 增量：单个更新
    fun handlePresenceEvent(userId: String, isOnline: Boolean) {
        _onlineUsers[userId] = isOnline
    }

    fun isOnline(userId: String): Boolean = _onlineUsers[userId] == true
}

// 在对话列表/聊天页面显示在线状态
@Composable
fun OnlineIndicator(userId: String, presenceManager: PresenceManager) {
    val isOnline = presenceManager.isOnline(userId)
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                color = if (isOnline) Color.Green else Color.Gray,
                shape = CircleShape
            )
    )
}
```

```typescript
// Web - 在线状态管理
const onlineUsers = new Map<string, boolean>();

// 快照：清空旧状态，批量初始化
function handlePresenceSnapshot(users: { userId: string; isOnline: boolean }[]) {
  onlineUsers.clear();
  for (const user of users) {
    onlineUsers.set(user.userId, user.isOnline);
  }
  updateOnlineStatus();
}

// 增量：单个更新
function handlePresenceChange(data: { userId: string; isOnline: boolean }) {
  onlineUsers.set(data.userId, data.isOnline);
  updateOnlineStatus();
}
```

**注意**:
- `presence_snapshot` 保证每次连接都会收到，客户端收到后应清空旧状态再写入（重连场景下避免脏数据）
- 多设备场景：用户从手机和电脑同时登录，只有当所有设备都断开连接后才会收到下线通知

### 9.7 客户端统一消息处理

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
                api.markAsRead(data.conversationId)
            }

            // 3. 如果在其他页面 → 显示通知横幅 / 更新未读 badge
            showNotificationBadge(data.senderDisplayName, data.contentPreview)
        }

        "messages_read" -> {
            val data = json.decodeFromString<MessagesReadEvent>(message.dataJson)
            chatState.markMessagesAsRead(data.conversationId, data.timestamp)
        }

        "message_recalled" -> {
            val data = json.decodeFromString<MessageRecalledEvent>(message.dataJson)
            // 更新本地消息列表：将对应消息标记为已撤回
            chatState.markMessageAsRecalled(data.messageId)
            // 如果在对话列表页，且该消息是最后一条消息，更新预览
            conversationListState.updateLastMessageIfRecalled(data.conversationId, data.messageId)
        }

        "typing_indicator" -> {
            val data = json.decodeFromString<TypingIndicatorEvent>(message.dataJson)
            // 仅当用户在该对话页面时显示打字状态
            if (currentConversationId == data.conversationId) {
                chatState.setOtherUserTyping(data.isTyping)
            }
        }

        "presence_snapshot" -> {
            val data = json.decodeFromString<PresenceSnapshotEvent>(message.dataJson)
            presenceManager.handlePresenceSnapshot(data.users)
        }

        "user_presence_changed" -> {
            val data = json.decodeFromString<UserPresenceEvent>(message.dataJson)
            presenceManager.handlePresenceEvent(data.userId, data.isOnline)
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
    case 'message_recalled':
      handleMessageRecalled(message.data);
      break;
    case 'typing_indicator':
      handleTypingIndicator(message.data);
      break;
    case 'presence_snapshot':
      handlePresenceSnapshot(message.data.users);
      break;
    case 'user_presence_changed':
      handlePresenceChange(message.data);
      break;
  }
};
```

---

## 10. TypeScript 类型定义

```typescript
// ========== Request ==========
interface SendMessageRequest {
  recipientId: string;
  content: string;
  imageUrl?: string;
  replyToMessageId?: string;       // [v2]
}

// ========== Response ==========
interface MessageResponse {
  id: string;
  conversationId: string;
  senderId: string;
  content: string;                  // 已删除/已撤回时为 ""
  imageUrl: string | null;          // 已删除/已撤回时为 null
  replyToMessageId: string | null;  // [v2]
  readAt: number | null;            // null = 未读, 数字 = 已读时间戳
  deletedAt: number | null;         // [v2] null = 未删除
  recalledAt: number | null;        // [v2] null = 未撤回
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

// ========== WebSocket Events (服务端 → 客户端) ==========
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

// [v2]
interface MessageRecalledEvent {
  messageId: string;
  conversationId: string;
  recalledByUserId: string;
  timestamp: number;
}

// [v2]
interface TypingIndicatorEvent {
  conversationId: string;
  userId: string;
  isTyping: boolean;
  timestamp: number;
}

// [v2]
interface UserPresenceEvent {
  userId: string;
  isOnline: boolean;
  timestamp: number;
}

// ========== WebSocket Messages (客户端 → 服务端) ==========
// [v2] 打字状态
interface TypingMessage {
  type: 'typing' | 'stop_typing';
  conversationId: string;
}

// 心跳
interface PingMessage {
  type: 'ping';
}

// Post 订阅
interface PostSubscriptionMessage {
  type: 'subscribe_post' | 'unsubscribe_post';
  postId: string;
}
```

---

## 11. Kotlin 数据类（Android / KMP）

```kotlin
// ========== Request ==========
@Serializable
data class SendMessageRequest(
    val recipientId: String,
    val content: String,
    val imageUrl: String? = null,
    val replyToMessageId: String? = null    // [v2]
)

// ========== Response ==========
@Serializable
data class MessageResponse(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val content: String,                     // 已删除/已撤回时为 ""
    val imageUrl: String?,                   // 已删除/已撤回时为 null
    val replyToMessageId: String?,           // [v2]
    val readAt: Long?,
    val deletedAt: Long?,                    // [v2] null = 未删除
    val recalledAt: Long?,                   // [v2] null = 未撤回
    val createdAt: Long
) {
    val isDeleted: Boolean get() = deletedAt != null
    val isRecalled: Boolean get() = recalledAt != null
    val isNormalMessage: Boolean get() = !isDeleted && !isRecalled
}

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

// ========== WebSocket Events ==========
@Serializable
data class NewMessageEvent(
    val messageId: String,
    val conversationId: String,
    val senderDisplayName: String,
    val senderUsername: String,
    val contentPreview: String,
    val timestamp: Long
)

@Serializable
data class MessagesReadEvent(
    val conversationId: String,
    val readByUserId: String,
    val timestamp: Long
)

// [v2]
@Serializable
data class MessageRecalledEvent(
    val messageId: String,
    val conversationId: String,
    val recalledByUserId: String,
    val timestamp: Long
)

// [v2]
@Serializable
data class TypingIndicatorEvent(
    val conversationId: String,
    val userId: String,
    val isTyping: Boolean,
    val timestamp: Long
)

// [v2]
@Serializable
data class UserPresenceEvent(
    val userId: String,
    val isOnline: Boolean,
    val timestamp: Long
)
```

---

## 12. 完整交互流程

### 12.1 首次发起对话

```
用户 A 给用户 B 发消息（A 和 B 之前没聊过）

A: POST /v1/conversations/messages
   { "recipientId": "B-id", "content": "Hi!" }

Server:
  1. 检查 B 的 dmPermission（如果 MUTUAL_FOLLOW，验证互关）
  2. 创建 Conversation (A, B)     ← 自动
  3. 保存 Message                 ← 自动
  4. 推送 WebSocket "new_message" 给 B  ← 异步

A ← 201 { id: "msg-1", conversationId: "conv-1", ... }
B ← WS  { type: "new_message", data: { messageId: "msg-1", ... } }
```

### 12.2 回复消息

```
B 回复 A 的消息（引用 msg-1）

B: POST /v1/conversations/messages
   { "recipientId": "A-id", "content": "你好！", "replyToMessageId": "msg-1" }

Server:
  1. 找到已有的 Conversation      ← 自动
  2. 保存 Message (replyToMessageId = "msg-1")
  3. 推送给 A

A ← WS { type: "new_message", data: { ... } }
B ← 201 { ..., "replyToMessageId": "msg-1" }
```

### 12.3 查看对话 + 已读

```
A 打开和 B 的对话

A: GET /v1/conversations/conv-1/messages?limit=50
A: PUT /v1/conversations/conv-1/read

Server:
  1. 返回消息历史（含已删除/已撤回消息的占位）
  2. 标记 B 发送的未读消息为已读
  3. 推送 "messages_read" 给 B
```

### 12.4 撤回消息

```
A 发送了一条消息后反悔（1 分钟内）

A: PUT /v1/messages/msg-2/recall

Server:
  1. 校验 A 是发送者
  2. 校验未超过 3 分钟
  3. 设置 recalled_at
  4. 推送 WebSocket "message_recalled" 给 B

A ← 200 { "messageId": "msg-2", "recalled": true }
B ← WS  { type: "message_recalled", data: { messageId: "msg-2", ... } }
```

### 12.5 打字状态

```
A 开始输入

A → WS { "type": "typing", "conversationId": "conv-1" }

Server:
  1. 查找 conv-1 的另一方（B）
  2. 推送 typing_indicator 给 B

B ← WS { type: "typing_indicator", data: { isTyping: true, ... } }
B 的 UI 显示 "A 正在输入..."

A 停止输入 3 秒后

A → WS { "type": "stop_typing", "conversationId": "conv-1" }

B ← WS { type: "typing_indicator", data: { isTyping: false, ... } }
B 的 UI 隐藏打字状态
```

---

## 13. 错误码汇总

| HTTP 状态 | code | message | 场景 |
|-----------|------|---------|------|
| `400` | `EMPTY_CONTENT` | 消息内容不能为空 | content 为空或空白 |
| `400` | `CONTENT_TOO_LONG` | 消息内容过长：最多 2000 字符 | content > 2000 字符 |
| `400` | `CANNOT_MESSAGE_SELF` | 不能给自己发送消息 | recipientId = 自己 |
| `400` | `MISSING_PARAM` | 缺少参数 | URL 参数缺失 |
| `400` | `RECALL_TIME_EXPIRED` | 消息撤回超时，仅支持 3 分钟内 | 超过 3 分钟撤回 |
| `401` | `UNAUTHORIZED` | 未授权访问 | 未携带或无效 JWT |
| `403` | `NOT_PARTICIPANT` | 您不是该对话的参与者 | 试图查看他人对话 |
| `403` | `NOT_MESSAGE_SENDER` | 只有消息发送者可以执行此操作 | 非发送者删除/撤回 |
| `403` | `DM_PERMISSION_DENIED` | 对方仅允许互关用户发消息 | 对方开启了互关限制 |
| `403` | `USER_BLOCKED` | 无法发送消息，用户已被拉黑 | 存在拉黑关系（双向） |
| `404` | `RECIPIENT_NOT_FOUND` | 接收者不存在 | recipientId 无效 |
| `404` | `CONVERSATION_NOT_FOUND` | 对话不存在 | conversationId 无效 |
| `404` | `MESSAGE_NOT_FOUND` | 消息不存在 | messageId 无效 |
| `409` | `MESSAGE_ALREADY_RECALLED` | 消息已被撤回 | 重复撤回 |
| `409` | `MESSAGE_ALREADY_DELETED` | 消息已被删除 | 重复删除或对已删除消息撤回 |

错误响应格式统一为：

```json
{
  "code": "ERROR_CODE",
  "message": "人类可读的错误描述",
  "timestamp": 1707600000000
}
```

---

## 14. 测试检查清单

### 基础流程

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 1 | A 发消息给 B（首次） | 201，自动创建对话 |
| 2 | B 发消息给 A | 201，复用同一个对话 |
| 3 | A 查对话列表 | 包含与 B 的对话，unreadCount = 1 |
| 4 | A 查消息历史 | 返回所有消息，按时间倒序 |
| 5 | A 标记已读 | B 的消息 readAt 被设置 |
| 6 | A 再查对话列表 | unreadCount = 0 |

### 回复消息 [v2]

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 7 | A 回复 B 的某条消息 | 201，返回的 replyToMessageId 不为 null |
| 8 | 查消息历史 | 回复消息中 replyToMessageId 正确指向原始消息 |

### 消息删除 [v2]

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 9 | A 删除自己的消息 | 204，消息历史中该消息 content 为空、deletedAt 不为 null |
| 10 | A 删除 B 的消息 | 403 `NOT_MESSAGE_SENDER` |
| 11 | A 再次删除同一条消息 | 409 `MESSAGE_ALREADY_DELETED` |
| 12 | 删除消息后查对话列表 | unreadCount 不计入已删除消息 |

### 消息撤回 [v2]

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 13 | A 发送消息后 1 分钟内撤回 | 200，B 收到 `message_recalled` WebSocket 事件 |
| 14 | A 发送消息后 4 分钟撤回 | 400 `RECALL_TIME_EXPIRED` |
| 15 | A 撤回 B 的消息 | 403 `NOT_MESSAGE_SENDER` |
| 16 | A 对同一条消息撤回两次 | 409 `MESSAGE_ALREADY_RECALLED` |
| 17 | 撤回已删除的消息 | 409 `MESSAGE_ALREADY_DELETED` |

### 打字状态 [v2]

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 18 | A 发送 typing 事件 | B 收到 `typing_indicator(isTyping=true)` |
| 19 | A 发送 stop_typing 事件 | B 收到 `typing_indicator(isTyping=false)` |
| 20 | A 发送 typing 到不存在的对话 | WebSocket 返回 error |

### 在线状态 [v2]

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 21 | A 连接 WebSocket | 广播 `user_presence_changed(isOnline=true)` |
| 22 | A 断开最后一个 WebSocket 连接 | 广播 `user_presence_changed(isOnline=false)` |
| 23 | A 有两个设备连接，断开一个 | 不广播下线（仍有一个设备在线） |

### DM 权限 [v2]

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 24 | B 设置 dmPermission=MUTUAL_FOLLOW，A 未关注 B | 403 `DM_PERMISSION_DENIED` |
| 25 | B 设置 dmPermission=MUTUAL_FOLLOW，A 和 B 互关 | 201 发送成功 |
| 26 | B 设置 dmPermission=EVERYONE | 201 任何人都能发送 |

### 边界和错误

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 27 | 发空消息 | 400 `EMPTY_CONTENT` |
| 28 | 发 2001 字符消息 | 400 `CONTENT_TOO_LONG` |
| 29 | 发消息给自己 | 400 `CANNOT_MESSAGE_SELF` |
| 30 | 发消息给不存在的用户 | 404 `RECIPIENT_NOT_FOUND` |
| 31 | 查看不存在的对话消息 | 404 `CONVERSATION_NOT_FOUND` |
| 32 | 查看他人对话的消息 | 403 `NOT_PARTICIPANT` |

### 实时通知

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 33 | A 发消息，B 在线 | B 的 WebSocket 收到 `new_message` |
| 34 | A 发消息，B 离线 | 无 WebSocket 推送（消息已持久化，B 上线后从 API 获取） |
| 35 | A 标记已读 | B 的 WebSocket 收到 `messages_read` |
