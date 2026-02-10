# 实时通知功能 - 客户端使用指南

## 概述

本指南展示如何在客户端使用WebSocket实时通知功能，包括连接建立、消息订阅和处理。

---

## WebSocket 端点

```
ws://localhost:8080/v1/notifications/ws
```

**认证要求**: 必须携带有效的JWT Token

---

## 连接建立

### JavaScript/TypeScript 示例

```typescript
class NotificationClient {
    private ws: WebSocket | null = null;
    private token: string;

    constructor(token: string) {
        this.token = token;
    }

    connect() {
        // 注意：WebSocket 不支持直接在构造函数中传递 headers
        // 需要在 URL 中传递 token 或使用其他方式
        const url = `ws://localhost:8080/v1/notifications/ws`;

        this.ws = new WebSocket(url);

        // 连接建立后，发送认证信息
        this.ws.onopen = this.handleOpen.bind(this);
        this.ws.onmessage = this.handleMessage.bind(this);
        this.ws.onerror = this.handleError.bind(this);
        this.ws.onclose = this.handleClose.bind(this);
    }

    private handleOpen(event: Event) {
        console.log('WebSocket connected');
        // Ktor WebSocket 会在 authenticate 中处理 JWT
        // 连接成功后会收到 connected 消息
    }

    private handleMessage(event: MessageEvent) {
        const message = JSON.parse(event.data);
        this.processMessage(message);
    }

    private handleError(event: Event) {
        console.error('WebSocket error:', event);
    }

    private handleClose(event: CloseEvent) {
        console.log('WebSocket disconnected:', event.code, event.reason);
        // 实现自动重连
        setTimeout(() => this.connect(), 5000);
    }

    private processMessage(message: any) {
        switch (message.type) {
            case 'connected':
                console.log('Successfully authenticated as user:', message.userId);
                break;
            case 'new_post':
                this.handleNewPost(message.data);
                break;
            case 'post_liked':
                this.handlePostLiked(message.data);
                break;
            case 'post_commented':
                this.handlePostCommented(message.data);
                break;
            case 'subscribed':
                console.log('Subscribed to post:', message.postId);
                break;
            case 'unsubscribed':
                console.log('Unsubscribed from post:', message.postId);
                break;
            case 'pong':
                console.log('Pong received');
                break;
            case 'error':
                console.error('Server error:', message.message);
                break;
            default:
                console.warn('Unknown message type:', message.type);
        }
    }

    subscribeToPost(postId: string) {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify({
                type: 'subscribe_post',
                postId: postId
            }));
        }
    }

    unsubscribeFromPost(postId: string) {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify({
                type: 'unsubscribe_post',
                postId: postId
            }));
        }
    }

    ping() {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify({ type: 'ping' }));
        }
    }

    disconnect() {
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
    }

    // ========== 事件处理器 ==========

    private handleNewPost(data: any) {
        console.log('New post created:', data);
        // 在时间线顶部显示"有新内容"提示
        showNewPostNotification({
            postId: data.postId,
            author: data.authorDisplayName,
            username: data.authorUsername,
            content: data.content,
            createdAt: data.createdAt
        });
    }

    private handlePostLiked(data: any) {
        console.log('Post liked:', data);
        // 更新UI中的点赞数
        updatePostLikeCount(data.postId, data.newLikeCount);

        // 显示点赞动画
        showLikeAnimation(data.postId, data.likedByDisplayName);
    }

    private handlePostCommented(data: any) {
        console.log('Post commented:', data);
        // 显示新评论通知
        showNewCommentNotification({
            postId: data.postId,
            commenter: data.commentedByDisplayName,
            commentId: data.commentId,
            preview: data.commentPreview
        });
    }
}

// ========== UI 辅助函数（示例） ==========

function showNewPostNotification(postData: any) {
    // 在页面顶部显示横幅
    const banner = document.createElement('div');
    banner.className = 'new-post-banner';
    banner.innerHTML = `
        <span>@${postData.username} 发布了新内容</span>
        <button onclick="loadNewPosts()">查看</button>
    `;
    document.body.prepend(banner);
}

function updatePostLikeCount(postId: string, newCount: number) {
    const likeCountElement = document.querySelector(`[data-post-id="${postId}"] .like-count`);
    if (likeCountElement) {
        likeCountElement.textContent = newCount.toString();

        // 添加动画效果
        likeCountElement.classList.add('count-updated');
        setTimeout(() => {
            likeCountElement.classList.remove('count-updated');
        }, 500);
    }
}

function showLikeAnimation(postId: string, userName: string) {
    const postElement = document.querySelector(`[data-post-id="${postId}"]`);
    if (postElement) {
        const animation = document.createElement('div');
        animation.className = 'like-animation';
        animation.textContent = `${userName} 赞了这条内容`;
        postElement.appendChild(animation);

        setTimeout(() => animation.remove(), 3000);
    }
}

function showNewCommentNotification(commentData: any) {
    // 显示新评论通知
    console.log('New comment:', commentData);
    // 实现具体的UI更新逻辑
}
```

---

## React 集成示例

```typescript
import { useEffect, useState, useRef } from 'react';

function useNotifications(token: string) {
    const wsRef = useRef<WebSocket | null>(null);
    const [isConnected, setIsConnected] = useState(false);
    const [newPosts, setNewPosts] = useState<any[]>([]);

    useEffect(() => {
        const ws = new WebSocket('ws://localhost:8080/v1/notifications/ws');

        ws.onopen = () => {
            console.log('WebSocket connected');
            setIsConnected(true);
        };

        ws.onmessage = (event) => {
            const message = JSON.parse(event.data);

            switch (message.type) {
                case 'new_post':
                    setNewPosts(prev => [message.data, ...prev]);
                    break;
                case 'post_liked':
                    // 更新状态
                    break;
            }
        };

        ws.onclose = () => {
            console.log('WebSocket disconnected');
            setIsConnected(false);
        };

        wsRef.current = ws;

        return () => {
            ws.close();
        };
    }, [token]);

    const subscribeToPost = (postId: string) => {
        if (wsRef.current && isConnected) {
            wsRef.current.send(JSON.stringify({
                type: 'subscribe_post',
                postId
            }));
        }
    };

    const unsubscribeFromPost = (postId: string) => {
        if (wsRef.current && isConnected) {
            wsRef.current.send(JSON.stringify({
                type: 'unsubscribe_post',
                postId
            }));
        }
    };

    return { isConnected, newPosts, subscribeToPost, unsubscribeFromPost };
}

// 在组件中使用
function TimelinePage() {
    const { isConnected, newPosts, subscribeToPost } = useNotifications(authToken);

    return (
        <div>
            <div className="connection-status">
                {isConnected ? '🟢 实时同步中' : '🔴 已断开'}
            </div>

            {newPosts.length > 0 && (
                <button onClick={() => loadNewPosts()}>
                    有 {newPosts.length} 条新内容
                </button>
            )}

            {/* Timeline content */}
        </div>
    );
}

function PostDetailPage({ postId }: { postId: string }) {
    const { subscribeToPost, unsubscribeFromPost } = useNotifications(authToken);

    useEffect(() => {
        // 进入页面时订阅
        subscribeToPost(postId);

        // 离开页面时取消订阅
        return () => {
            unsubscribeFromPost(postId);
        };
    }, [postId]);

    return <div>Post detail...</div>;
}
```

---

## Kotlin Multiplatform 客户端示例

```kotlin
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class NotificationClient(
    private val token: String,
    private val onNewPost: (NewPostData) -> Unit,
    private val onPostLiked: (PostLikedData) -> Unit
) {
    private val client = HttpClient {
        install(WebSockets)
    }

    private var session: DefaultClientWebSocketSession? = null

    suspend fun connect() {
        client.webSocket(
            host = "localhost",
            port = 8080,
            path = "/v1/notifications/ws"
        ) {
            session = this

            // 发送认证（如果需要）
            // send(Frame.Text("""{"token":"$token"}"""))

            // 接收消息
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    handleMessage(text)
                }
            }
        }
    }

    private fun handleMessage(text: String) {
        val json = Json { ignoreUnknownKeys = true }
        val message = json.parseToJsonElement(text).jsonObject

        when (message["type"]?.jsonPrimitive?.content) {
            "new_post" -> {
                // 解析并处理新 Post
                onNewPost(parseNewPost(message))
            }
            "post_liked" -> {
                // 解析并处理点赞事件
                onPostLiked(parsePostLiked(message))
            }
        }
    }

    suspend fun subscribeToPost(postId: String) {
        session?.send(Frame.Text("""{"type":"subscribe_post","postId":"$postId"}"""))
    }

    suspend fun unsubscribeFromPost(postId: String) {
        session?.send(Frame.Text("""{"type":"unsubscribe_post","postId":"$postId"}"""))
    }

    fun disconnect() {
        client.close()
    }
}
```

---

## 消息格式规范

### 服务端 → 客户端

#### 1. 连接成功

```json
{
    "type": "connected",
    "userId": "user-id-here"
}
```

#### 2. 新 Post 创建

```json
{
    "type": "new_post",
    "data": {
        "postId": "post-id",
        "authorId": "author-id",
        "authorDisplayName": "John Doe",
        "authorUsername": "johndoe",
        "content": "Hello, world!",
        "createdAt": 1234567890
    }
}
```

#### 3. Post 被点赞

```json
{
    "type": "post_liked",
    "data": {
        "postId": "post-id",
        "likedByUserId": "user-id",
        "likedByDisplayName": "Jane Smith",
        "likedByUsername": "janesmith",
        "newLikeCount": 42,
        "timestamp": 1234567890
    }
}
```

#### 4. Post 被评论（未来扩展）

```json
{
    "type": "post_commented",
    "data": {
        "postId": "post-id",
        "commentedByUserId": "user-id",
        "commentedByDisplayName": "Alice",
        "commentedByUsername": "alice",
        "commentId": "comment-id",
        "commentPreview": "Great post!",
        "timestamp": 1234567890
    }
}
```

#### 5. 订阅确认

```json
{
    "type": "subscribed",
    "postId": "post-id"
}
```

#### 6. 取消订阅确认

```json
{
    "type": "unsubscribed",
    "postId": "post-id"
}
```

#### 7. Pong 响应

```json
{
    "type": "pong"
}
```

#### 8. 错误消息

```json
{
    "type": "error",
    "message": "Error description"
}
```

### 客户端 → 服务端

#### 1. 订阅 Post

```json
{
    "type": "subscribe_post",
    "postId": "post-id"
}
```

#### 2. 取消订阅 Post

```json
{
    "type": "unsubscribe_post",
    "postId": "post-id"
}
```

#### 3. 心跳

```json
{
    "type": "ping"
}
```

---

## 最佳实践

### 1. 自动重连

```typescript
class RobustNotificationClient {
    private reconnectAttempts = 0;
    private maxReconnectAttempts = 5;
    private reconnectDelay = 1000;

    connect() {
        this.ws = new WebSocket(this.url);

        this.ws.onclose = (event) => {
            if (this.reconnectAttempts < this.maxReconnectAttempts) {
                const delay = this.reconnectDelay * Math.pow(2, this.reconnectAttempts);
                console.log(`Reconnecting in ${delay}ms...`);

                setTimeout(() => {
                    this.reconnectAttempts++;
                    this.connect();
                }, delay);
            }
        };

        this.ws.onopen = () => {
            this.reconnectAttempts = 0; // 重置计数
        };
    }
}
```

### 2. 心跳保活

```typescript
class NotificationClientWithHeartbeat {
    private heartbeatInterval: any;

    connect() {
        this.ws = new WebSocket(this.url);

        this.ws.onopen = () => {
            // 每30秒发送一次心跳
            this.heartbeatInterval = setInterval(() => {
                this.ping();
            }, 30000);
        };

        this.ws.onclose = () => {
            clearInterval(this.heartbeatInterval);
        };
    }
}
```

### 3. 订阅管理

```typescript
class SubscriptionManager {
    private subscribedPosts = new Set<string>();

    enterPostPage(postId: string) {
        if (!this.subscribedPosts.has(postId)) {
            this.notificationClient.subscribeToPost(postId);
            this.subscribedPosts.add(postId);
        }
    }

    leavePostPage(postId: string) {
        if (this.subscribedPosts.has(postId)) {
            this.notificationClient.unsubscribeFromPost(postId);
            this.subscribedPosts.delete(postId);
        }
    }
}
```

---

## 故障排查

### 连接失败

- 检查 JWT Token 是否有效
- 检查服务器是否启用 WebSocket 插件
- 检查网络连接和防火墙设置

### 消息丢失

- 实现消息确认机制
- 在重连后拉取离线期间的更新

### 性能问题

- 限制订阅的 Post 数量
- 实现消息节流（避免频繁更新）
- 使用虚拟滚动优化长列表

---

## 总结

实时通知功能通过 WebSocket 提供低延迟的双向通信，支持：

- 新 Post 全局广播
- Post 点赞实时更新
- 灵活的订阅管理

客户端需要：
1. 建立认证连接
2. 处理各种事件类型
3. 实现自动重连和心跳
4. 管理订阅生命周期

完整的服务端设计和实现请参考 `realtime-notification-design.md`。
