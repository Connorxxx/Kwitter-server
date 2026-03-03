package com.connor.infrastructure.repository

import com.connor.domain.model.NotificationEvent
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.repository.NotificationRepository
import com.connor.infrastructure.sse.SseConnectionManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class InMemoryNotificationRepository(
    private val connectionManager: SseConnectionManager
) : NotificationRepository {
    private val logger = LoggerFactory.getLogger(InMemoryNotificationRepository::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun broadcastNewPost(event: NotificationEvent.NewPostCreated) {
        try {
            connectionManager.broadcastToAll("new_post", json.encodeToString(event))
            logger.info("Broadcasted new post: postId={}", event.postId)
        } catch (e: Exception) {
            logger.error("Failed to broadcast new post: postId={}", event.postId, e)
        }
    }

    override suspend fun notifyPostLiked(event: NotificationEvent.PostLiked) {
        try {
            connectionManager.sendToPostSubscribers(PostId(event.postId), "post_liked", json.encodeToString(event))
            logger.info("Notified post liked: postId={}, newLikeCount={}", event.postId, event.newLikeCount)
        } catch (e: Exception) {
            logger.error("Failed to notify post liked: postId={}", event.postId, e)
        }
    }

    override suspend fun notifyPostUnliked(event: NotificationEvent.PostUnliked) {
        try {
            connectionManager.sendToPostSubscribers(PostId(event.postId), "post_unliked", json.encodeToString(event))
            logger.info("Notified post unliked: postId={}, newLikeCount={}", event.postId, event.newLikeCount)
        } catch (e: Exception) {
            logger.error("Failed to notify post unliked: postId={}", event.postId, e)
        }
    }

    override suspend fun notifyPostCommented(event: NotificationEvent.PostCommented) {
        try {
            connectionManager.sendToPostSubscribers(PostId(event.postId), "post_commented", json.encodeToString(event))
            logger.info("Notified post commented: postId={}, commentId={}", event.postId, event.commentId)
        } catch (e: Exception) {
            logger.error("Failed to notify post commented: postId={}", event.postId, e)
        }
    }

    override suspend fun notifyNewMessage(recipientId: UserId, event: NotificationEvent.NewMessageReceived) {
        try {
            connectionManager.sendToUser(recipientId, "new_message", json.encodeToString(event))
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
            connectionManager.sendToUser(recipientId, "messages_read", json.encodeToString(event))
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
            connectionManager.sendToUser(recipientId, "message_recalled", json.encodeToString(event))
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
            connectionManager.sendToUser(recipientId, "typing_indicator", json.encodeToString(event))
            logger.debug(
                "Notified typing indicator: recipientId={}, conversationId={}, isTyping={}",
                recipientId.value, event.conversationId, event.isTyping
            )
        } catch (e: Exception) {
            logger.error("Failed to notify typing indicator: recipientId={}", recipientId.value, e)
        }
    }
}
