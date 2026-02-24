package com.connor.domain.model

import kotlinx.serialization.Serializable

/**
 * 通知事件密封接口
 *
 * 表示系统中可能发生的各种需要实时推送的事件
 */
sealed interface NotificationEvent {
    /**
     * 新 Post 创建事件
     *
     * 触发条件：用户创建顶层 Post（非回复）
     * 推送对象：所有在线用户
     */
    @Serializable
    data class NewPostCreated(
        val postId: String,
        val authorId: String,
        val authorDisplayName: String,
        val authorUsername: String,
        val content: String,
        val createdAt: Long
    ) : NotificationEvent

    /**
     * Post 被点赞事件
     *
     * 触发条件：用户点赞某个 Post
     * 推送对象：订阅该 Post 的所有用户
     */
    @Serializable
    data class PostLiked(
        val postId: String,
        val likedByUserId: String,
        val likedByDisplayName: String,
        val likedByUsername: String,
        val newLikeCount: Int,
        val timestamp: Long
    ) : NotificationEvent

    /**
     * Post 被评论/回复事件（未来扩展）
     *
     * 触发条件：用户回复某个 Post
     * 推送对象：Post 作者和订阅该 Post 的用户
     */
    @Serializable
    data class PostCommented(
        val postId: String,
        val commentedByUserId: String,
        val commentedByDisplayName: String,
        val commentedByUsername: String,
        val commentId: String,
        val commentPreview: String,
        val timestamp: Long
    ) : NotificationEvent

    /**
     * 新私信接收事件
     *
     * 触发条件：用户收到私信
     * 推送对象：消息接收者
     */
    @Serializable
    data class NewMessageReceived(
        val messageId: String,
        val conversationId: String,
        val senderDisplayName: String,
        val senderUsername: String,
        val contentPreview: String,
        val timestamp: Long
    ) : NotificationEvent

    /**
     * 私信已读事件
     *
     * 触发条件：用户标记对话已读
     * 推送对象：消息发送者（通知对方消息已被阅读）
     */
    @Serializable
    data class MessagesRead(
        val conversationId: String,
        val readByUserId: String,
        val timestamp: Long
    ) : NotificationEvent

    /**
     * 消息撤回事件
     *
     * 触发条件：用户撤回消息（3 分钟内）
     * 推送对象：对话中的另一方
     */
    @Serializable
    data class MessageRecalled(
        val messageId: String,
        val conversationId: String,
        val recalledByUserId: String,
        val timestamp: Long
    ) : NotificationEvent

    /**
     * 打字状态事件
     *
     * 触发条件：用户正在输入/停止输入
     * 推送对象：对话中的另一方
     * 不持久化
     */
    @Serializable
    data class TypingIndicator(
        val conversationId: String,
        val userId: String,
        val isTyping: Boolean,
        val timestamp: Long
    ) : NotificationEvent
}

/**
 * 通知推送目标
 *
 * 定义通知应该推送给哪些用户
 */
sealed interface NotificationTarget {
    /**
     * 广播给所有在线用户
     * 用于：新 Post 创建事件
     */
    data object Everyone : NotificationTarget

    /**
     * 推送给特定用户
     * 用于：私信、@提及等（未来扩展）
     */
    data class SpecificUser(val userId: UserId) : NotificationTarget

    /**
     * 推送给订阅特定 Post 的用户
     * 用于：Post 被点赞、被评论等事件
     */
    data class PostSubscribers(val postId: PostId) : NotificationTarget
}

/**
 * 客户端发送的 WebSocket 消息
 *
 * 定义客户端可以发送的各种控制消息
 */
sealed interface WebSocketClientMessage {
    /**
     * 订阅特定 Post 的更新
     *
     * 使用场景：用户进入 Post 详情页时
     */
    data class SubscribeToPost(val postId: PostId) : WebSocketClientMessage

    /**
     * 取消订阅 Post
     *
     * 使用场景：用户离开 Post 详情页时
     */
    data class UnsubscribeFromPost(val postId: PostId) : WebSocketClientMessage

    /**
     * 心跳包
     *
     * 用于保持连接活跃，防止超时断开
     */
    data object Ping : WebSocketClientMessage
}
