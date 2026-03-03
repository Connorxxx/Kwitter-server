# 实时通知功能 - 客户端使用指南

## 概述

本指南展示如何在客户端使用 SSE (Server-Sent Events) 实时通知功能，包括 SSE 连接建立、事件处理和 REST 命令调用。

---

## SSE Stream 端点

```
GET http://localhost:8080/v1/notifications/stream
Authorization: Bearer <jwt>
Accept: text/event-stream
```

**认证要求**: 必须携带有效的 JWT Token（标准 `Authorization` 头）

---

## SSE 事件类型

### 服务端 → 客户端（SSE 事件流）

所有事件通过 SSE `event` 字段区分类型，`data` 字段为 JSON 负载：

#### 1. 连接成功

```
event: connected
id: 1
data: {"userId":123}
```

#### 2. 在线状态快照（每次连接必发）

```
event: presence_snapshot
id: 2
data: {"users":[{"userId":456,"isOnline":true,"timestamp":1707600000000}]}
```

#### 3. 在线状态变更（增量）

```
event: user_presence_changed
id: 3
data: {"userId":456,"isOnline":true,"timestamp":1707600000000}
```

#### 4. 新 Post 创建

```
event: new_post
id: 10
data: {"postId":123,"authorId":456,"authorDisplayName":"John","authorUsername":"john","content":"Hello!","createdAt":1707600000000}
```

#### 5. Post 被点赞

```
event: post_liked
id: 11
data: {"postId":123,"likedByUserId":789,"likedByDisplayName":"Jane","likedByUsername":"jane","newLikeCount":42,"timestamp":1707600000000}
```

#### 5.1 Post 被取消点赞

```
event: post_unliked
id: 12
data: {"postId":123,"unlikedByUserId":789,"newLikeCount":41,"isLiked":false,"timestamp":1707600000000}
```

#### 6. 新私信

```
event: new_message
id: 20
data: {"messageId":100,"conversationId":50,"senderDisplayName":"Alice","senderUsername":"alice","contentPreview":"Hey!","timestamp":1707600000000}
```

#### 7. 消息已读

```
event: messages_read
id: 21
data: {"conversationId":50,"readByUserId":456,"timestamp":1707600000000}
```

#### 8. 消息撤回

```
event: message_recalled
id: 22
data: {"messageId":100,"conversationId":50,"recalledByUserId":789,"timestamp":1707600000000}
```

#### 9. 打字状态

```
event: typing_indicator
id: 23
data: {"conversationId":50,"userId":456,"isTyping":true,"timestamp":1707600000000}
```

#### 10. 会话撤销

```
event: auth_revoked
id: 99
data: Session revoked by server
```

#### 11. 心跳（注释行）

```
:heartbeat
```

---

## 客户端 → 服务端（REST 端点）

| 操作 | 方法 | 路径 | 请求体 |
|------|------|------|--------|
| 订阅 Post | `POST` | `/v1/notifications/posts/{postId}/subscribe` | — |
| 取消订阅 Post | `DELETE` | `/v1/notifications/posts/{postId}/subscribe` | — |
| 发送打字状态 | `PUT` | `/v1/messaging/conversations/{conversationId}/typing` | `{"isTyping":true}` |

Ping/pong 已消除 — SSE 心跳由服务端自动发送。

---

## Kotlin Multiplatform 客户端示例

```kotlin
class NotificationService(
    private val httpClient: HttpClient,
    private val baseUrl: String
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _notificationEvents = MutableSharedFlow<NotificationEvent>(extraBufferCapacity = 64)
    val notificationEvents: SharedFlow<NotificationEvent> = _notificationEvents.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var connectionJob: Job? = null

    fun connect(scope: CoroutineScope) {
        disconnect()
        connectionJob = scope.launch {
            var retryCount = 0
            while (isActive) {
                _connectionState.value = ConnectionState.Connecting
                try {
                    httpClient.sse(
                        urlString = "$baseUrl/v1/notifications/stream",
                        showCommentEvents = false
                    ) {
                        _connectionState.value = ConnectionState.Connected
                        retryCount = 0
                        incoming.collect { event ->
                            handleSseEvent(event.event, event.data)
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { }

                _connectionState.value = ConnectionState.Disconnected
                if (!isActive) break
                val delayMs = BACKOFF_DELAYS[retryCount.coerceAtMost(BACKOFF_DELAYS.lastIndex)]
                delay(delayMs)
                retryCount++
            }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private suspend fun handleSseEvent(eventType: String?, data: String?) {
        when (eventType) {
            "new_post" -> parseData<NotificationEvent.NewPostCreated>(data)?.let { _notificationEvents.emit(it) }
            "post_liked" -> parseData<NotificationEvent.PostLiked>(data)?.let { _notificationEvents.emit(it) }
            "post_unliked" -> parseData<NotificationEvent.PostUnliked>(data)?.let { _notificationEvents.emit(it) }
            "new_message" -> parseData<NotificationEvent.NewMessage>(data)?.let { _notificationEvents.emit(it) }
            "messages_read" -> parseData<NotificationEvent.MessagesRead>(data)?.let { _notificationEvents.emit(it) }
            "message_recalled" -> parseData<NotificationEvent.MessageRecalled>(data)?.let { _notificationEvents.emit(it) }
            "typing_indicator" -> parseData<NotificationEvent.TypingIndicator>(data)?.let { _notificationEvents.emit(it) }
            "presence_snapshot" -> parseData<NotificationEvent.PresenceSnapshot>(data)?.let { _notificationEvents.emit(it) }
            "user_presence_changed" -> parseData<NotificationEvent.UserPresenceChanged>(data)?.let { _notificationEvents.emit(it) }
            "auth_revoked" -> { /* handle force logout */ }
            "connected", "subscribed", "unsubscribed" -> { /* ack */ }
        }
    }

    // REST 命令
    suspend fun subscribeToPost(postId: Long) {
        httpClient.post("$baseUrl/v1/notifications/posts/$postId/subscribe")
    }

    suspend fun unsubscribeFromPost(postId: Long) {
        httpClient.delete("$baseUrl/v1/notifications/posts/$postId/subscribe")
    }

    suspend fun sendTyping(conversationId: Long, isTyping: Boolean) {
        httpClient.put("$baseUrl/v1/messaging/conversations/$conversationId/typing") {
            contentType(ContentType.Application.Json)
            setBody(TypingRequest(isTyping))
        }
    }

    companion object {
        val BACKOFF_DELAYS = longArrayOf(1000, 2000, 4000, 8000, 16000)
    }
}
```

---

## JavaScript/TypeScript 客户端示例

```typescript
class NotificationClient {
    private eventSource: EventSource | null = null;
    private token: string;
    private reconnectAttempts = 0;
    private maxReconnectAttempts = 5;
    private reconnectDelay = 1000;

    constructor(token: string) {
        this.token = token;
    }

    connect() {
        // EventSource 不支持自定义头，需要使用 polyfill 或 fetch-based SSE
        // 例如使用 eventsource 库 (npm install eventsource)
        this.eventSource = new EventSource(
            'http://localhost:8080/v1/notifications/stream',
            { headers: { 'Authorization': `Bearer ${this.token}` } }
        );

        // 按 event type 注册监听器
        this.eventSource.addEventListener('connected', (e) => {
            console.log('Connected:', JSON.parse(e.data));
            this.reconnectAttempts = 0;
        });

        this.eventSource.addEventListener('new_post', (e) => {
            this.handleNewPost(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('post_liked', (e) => {
            this.handlePostLiked(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('post_unliked', (e) => {
            this.handlePostUnliked(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('new_message', (e) => {
            this.handleNewMessage(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('messages_read', (e) => {
            this.handleMessagesRead(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('message_recalled', (e) => {
            this.handleMessageRecalled(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('typing_indicator', (e) => {
            this.handleTypingIndicator(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('presence_snapshot', (e) => {
            this.handlePresenceSnapshot(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('user_presence_changed', (e) => {
            this.handlePresenceChanged(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('auth_revoked', (e) => {
            this.handleAuthRevoked(e.data);
        });

        this.eventSource.onerror = () => {
            console.error('SSE connection error');
            this.eventSource?.close();
            this.attemptReconnect();
        };
    }

    disconnect() {
        this.eventSource?.close();
        this.eventSource = null;
    }

    private attemptReconnect() {
        if (this.reconnectAttempts < this.maxReconnectAttempts) {
            const delay = this.reconnectDelay * Math.pow(2, this.reconnectAttempts);
            setTimeout(() => {
                this.reconnectAttempts++;
                this.connect();
            }, delay);
        }
    }

    // REST 命令
    async subscribeToPost(postId: number) {
        await fetch(`http://localhost:8080/v1/notifications/posts/${postId}/subscribe`, {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${this.token}` }
        });
    }

    async unsubscribeFromPost(postId: number) {
        await fetch(`http://localhost:8080/v1/notifications/posts/${postId}/subscribe`, {
            method: 'DELETE',
            headers: { 'Authorization': `Bearer ${this.token}` }
        });
    }

    async sendTyping(conversationId: number, isTyping: boolean) {
        await fetch(`http://localhost:8080/v1/messaging/conversations/${conversationId}/typing`, {
            method: 'PUT',
            headers: {
                'Authorization': `Bearer ${this.token}`,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ isTyping })
        });
    }

    // 事件处理器
    private handleNewPost(data: any) { /* UI 更新 */ }
    private handlePostLiked(data: any) { /* 更新点赞数 */ }
    private handlePostUnliked(data: any) { /* 更新点赞数 */ }
    private handleNewMessage(data: any) { /* 显示新消息 */ }
    private handleMessagesRead(data: any) { /* 更新已读状态 */ }
    private handleMessageRecalled(data: any) { /* 标记消息撤回 */ }
    private handleTypingIndicator(data: any) { /* 显示/隐藏打字指示器 */ }
    private handlePresenceSnapshot(data: any) { /* 初始化在线状态 */ }
    private handlePresenceChanged(data: any) { /* 更新在线状态 */ }
    private handleAuthRevoked(message: string) { /* 强制下线 */ }
}
```

---

## React 集成示例

```typescript
import { useEffect, useState, useRef, useCallback } from 'react';

function useNotifications(token: string) {
    const clientRef = useRef<NotificationClient | null>(null);
    const [isConnected, setIsConnected] = useState(false);
    const [newPosts, setNewPosts] = useState<any[]>([]);

    useEffect(() => {
        const client = new NotificationClient(token);
        clientRef.current = client;
        client.connect();

        // 连接状态由 SSE EventSource readyState 驱动
        // 实际项目中可用 zustand/jotai 管理

        return () => {
            client.disconnect();
        };
    }, [token]);

    const subscribeToPost = useCallback((postId: number) => {
        clientRef.current?.subscribeToPost(postId);
    }, []);

    const unsubscribeFromPost = useCallback((postId: number) => {
        clientRef.current?.unsubscribeFromPost(postId);
    }, []);

    return { isConnected, newPosts, subscribeToPost, unsubscribeFromPost };
}

function PostDetailPage({ postId }: { postId: number }) {
    const { subscribeToPost, unsubscribeFromPost } = useNotifications(authToken);

    useEffect(() => {
        subscribeToPost(postId);
        return () => { unsubscribeFromPost(postId); };
    }, [postId]);

    return <div>Post detail...</div>;
}
```

---

## 手动测试 (curl)

### 连接 SSE 流

```bash
curl -N -H "Authorization: Bearer <token>" \
  http://localhost:8080/v1/notifications/stream
```

**预期输出**:
```
event: connected
id: 1
data: {"userId":123}

event: presence_snapshot
id: 2
data: {"users":[]}

:heartbeat

:heartbeat
```

### Post 订阅

```bash
# 订阅
curl -X POST -H "Authorization: Bearer <token>" \
  http://localhost:8080/v1/notifications/posts/123/subscribe
# → 200 OK

# 取消订阅
curl -X DELETE -H "Authorization: Bearer <token>" \
  http://localhost:8080/v1/notifications/posts/123/subscribe
# → 204 No Content
```

### 打字状态

```bash
curl -X PUT -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"isTyping":true}' \
  http://localhost:8080/v1/messaging/conversations/50/typing
# → 204 No Content
```

---

## 最佳实践

### 1. 自动重连（指数退避）

SSE 原生支持 `Last-Event-ID` 自动重连。对于不支持的客户端，实现手动重连：

```kotlin
val BACKOFF_DELAYS = longArrayOf(1000, 2000, 4000, 8000, 16000)

while (isActive) {
    try {
        httpClient.sse("$baseUrl/v1/notifications/stream") {
            retryCount = 0
            incoming.collect { /* handle */ }
        }
    } catch (_: Exception) { }

    delay(BACKOFF_DELAYS[retryCount.coerceAtMost(BACKOFF_DELAYS.lastIndex)])
    retryCount++
}
```

### 2. 订阅管理

```kotlin
// 进入 Post 详情页 → 订阅
override fun observePostLikedEvents(postId: Long): Flow<NotificationEvent.PostLiked> =
    notificationService.notificationEvents
        .onStart { notificationService.subscribeToPost(postId) }
        .onCompletion {
            withContext(NonCancellable) {
                notificationService.unsubscribeFromPost(postId)
            }
        }.filterIsInstance()

// 订阅取消点赞事件
override fun observePostUnlikedEvents(postId: Long): Flow<NotificationEvent.PostUnliked> =
    notificationService.notificationEvents
        .onStart { notificationService.subscribeToPost(postId) }
        .onCompletion {
            withContext(NonCancellable) {
                notificationService.unsubscribeFromPost(postId)
            }
        }.filterIsInstance()

// 合并订阅点赞/取消点赞事件（推荐）
override fun observePostLikeChanges(postId: Long): Flow<NotificationEvent> =
    notificationService.notificationEvents
        .onStart { notificationService.subscribeToPost(postId) }
        .onCompletion {
            withContext(NonCancellable) {
                notificationService.unsubscribeFromPost(postId)
            }
        }.filter { it is NotificationEvent.PostLiked || it is NotificationEvent.PostUnliked }
```

### 3. 打字状态（带 debounce）

```kotlin
fun onTextChanged(text: String) {
    if (text.isNotEmpty() && !isCurrentlyTyping) {
        isCurrentlyTyping = true
        scope.launch { notificationService.sendTyping(conversationId, isTyping = true) }
    }

    typingJob?.cancel()
    typingJob = scope.launch {
        delay(3000)
        if (isCurrentlyTyping) {
            isCurrentlyTyping = false
            notificationService.sendTyping(conversationId, isTyping = false)
        }
    }
}
```

---

## 故障排查

### SSE 连接失败

- 检查 JWT Token 是否有效
- 检查服务端是否安装了 SSE 插件
- 检查 `Accept: text/event-stream` 头（部分代理可能需要）

### 事件丢失

- 确认 SSE 连接处于 `Connected` 状态
- 重连后通过 REST API 拉取最新数据（SSE 当前不支持历史重放）

### 命令失败

- REST 端点返回标准 HTTP 状态码，检查响应体中的错误信息
- 确认 JWT Token 仍然有效

---

## 总结

SSE + REST 方案相比之前的 WebSocket：

| 方面 | WebSocket (旧) | SSE + REST (新) |
|------|---------------|----------------|
| 推送方向 | 双向（实际主要单向） | 单向推送 + REST 命令 |
| 认证 | 需要特殊处理 | 标准 `Authorization` 头 |
| 重连 | 客户端自定义 | 原生 `Last-Event-ID` 支持 |
| 代理/CDN | 需要特殊配置 | HTTP 原生，开箱即用 |
| 心跳 | 客户端 ping + 服务端 pong | 服务端 `:heartbeat` 注释 |
| 命令 | JSON 消息通过 WebSocket 帧 | REST 端点（HTTP 状态码、限流） |
| 协议复杂度 | 帧协议 | 纯文本流 |

完整的服务端设计和实现请参考 `realtime-notification-design.md` 和 `realtime-notification-implementation-summary.md`。
