package com.connor.infrastructure.repository

import com.connor.domain.model.NotificationEvent
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.repository.NotificationRepository
import com.connor.infrastructure.websocket.WebSocketConnectionManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * 内存型通知 Repository 实现
 *
 * 职责：
 * - 实现 NotificationRepository 接口
 * - 将领域事件转换为 JSON 消息
 * - 通过 WebSocketConnectionManager 推送消息
 * - 处理推送失败（记录日志但不抛异常）
 *
 * 适用场景：
 * - 单机部署（< 10,000 并发连接）
 * - 原型验证和快速迭代
 *
 * 未来扩展：
 * - 分布式场景可实现 RedisNotificationRepository
 * - 使用 Redis Pub/Sub 实现跨服务器推送
 */
class InMemoryNotificationRepository(
    private val connectionManager: WebSocketConnectionManager
) : NotificationRepository {
    private val logger = LoggerFactory.getLogger(InMemoryNotificationRepository::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun broadcastNewPost(event: NotificationEvent.NewPostCreated) {
        try {
            val message = NotificationMessage(
                type = "new_post",
                data = event
            )

            val jsonMessage = json.encodeToString(message)
            connectionManager.broadcastToAll(jsonMessage)

            logger.info("Broadcasted new post: postId={}", event.postId)
        } catch (e: Exception) {
            logger.error("Failed to broadcast new post: postId={}", event.postId, e)
        }
    }

    override suspend fun notifyPostLiked(event: NotificationEvent.PostLiked) {
        try {
            val message = NotificationMessage(
                type = "post_liked",
                data = event
            )

            val jsonMessage = json.encodeToString(message)
            val postId = PostId(event.postId.toLong())
            connectionManager.sendToPostSubscribers(postId, jsonMessage)

            logger.info(
                "Notified post liked: postId={}, newLikeCount={}",
                event.postId,
                event.newLikeCount
            )
        } catch (e: Exception) {
            logger.error("Failed to notify post liked: postId={}", event.postId, e)
        }
    }

    override suspend fun notifyPostCommented(event: NotificationEvent.PostCommented) {
        try {
            val message = NotificationMessage(
                type = "post_commented",
                data = event
            )

            val jsonMessage = json.encodeToString(message)
            val postId = PostId(event.postId.toLong())
            connectionManager.sendToPostSubscribers(postId, jsonMessage)

            logger.info("Notified post commented: postId={}, commentId={}", event.postId, event.commentId)
        } catch (e: Exception) {
            logger.error("Failed to notify post commented: postId={}", event.postId, e)
        }
    }

    override suspend fun notifyNewMessage(recipientId: UserId, event: NotificationEvent.NewMessageReceived) {
        try {
            val message = NotificationMessage(
                type = "new_message",
                data = event
            )

            val jsonMessage = json.encodeToString(message)
            connectionManager.sendToUser(recipientId, jsonMessage)

            logger.info(
                "Notified new message: recipientId={}, messageId={}, conversationId={}",
                recipientId.value, event.messageId, event.conversationId
            )
        } catch (e: Exception) {
            logger.error("Failed to notify new message: recipientId={}, messageId={}", recipientId.value, event.messageId, e)
        }
    }

    override suspend fun notifyMessagesRead(recipientId: UserId, event: NotificationEvent.MessagesRead) {
        try {
            val message = NotificationMessage(
                type = "messages_read",
                data = event
            )

            val jsonMessage = json.encodeToString(message)
            connectionManager.sendToUser(recipientId, jsonMessage)

            logger.info(
                "Notified messages read: recipientId={}, conversationId={}",
                recipientId.value, event.conversationId
            )
        } catch (e: Exception) {
            logger.error("Failed to notify messages read: recipientId={}, conversationId={}", recipientId.value, event.conversationId, e)
        }
    }

    override suspend fun notifyMessageRecalled(recipientId: UserId, event: NotificationEvent.MessageRecalled) {
        try {
            val message = NotificationMessage(
                type = "message_recalled",
                data = event
            )

            val jsonMessage = json.encodeToString(message)
            connectionManager.sendToUser(recipientId, jsonMessage)

            logger.info(
                "Notified message recalled: recipientId={}, messageId={}",
                recipientId.value, event.messageId
            )
        } catch (e: Exception) {
            logger.error("Failed to notify message recalled: recipientId={}, messageId={}", recipientId.value, event.messageId, e)
        }
    }

    override suspend fun notifyTypingIndicator(recipientId: UserId, event: NotificationEvent.TypingIndicator) {
        try {
            val message = NotificationMessage(
                type = "typing_indicator",
                data = event
            )

            val jsonMessage = json.encodeToString(message)
            connectionManager.sendToUser(recipientId, jsonMessage)

            logger.debug(
                "Notified typing indicator: recipientId={}, conversationId={}, isTyping={}",
                recipientId.value, event.conversationId, event.isTyping
            )
        } catch (e: Exception) {
            logger.error("Failed to notify typing indicator: recipientId={}", recipientId.value, e)
        }
    }
}

/**
 * WebSocket 通知消息包装器
 *
 * 统一的消息格式，便于客户端解析
 */
@Serializable
private data class NotificationMessage<T>(
    val type: String,
    val data: T
)
