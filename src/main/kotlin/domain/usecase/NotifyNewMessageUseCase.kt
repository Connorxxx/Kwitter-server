package com.connor.domain.usecase

import com.connor.domain.model.*
import com.connor.domain.repository.MessageRepository
import com.connor.domain.repository.NotificationRepository
import com.connor.domain.service.PushNotificationService
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

class NotifyNewMessageUseCase(
    private val notificationRepository: NotificationRepository,
    private val pushNotificationService: PushNotificationService,
    private val messageRepository: MessageRepository
) {
    private val logger = LoggerFactory.getLogger(NotifyNewMessageUseCase::class.java)

    suspend fun execute(
        recipientId: UserId,
        messageId: MessageId,
        conversationId: ConversationId,
        senderDisplayName: String,
        senderUsername: String,
        contentPreview: String
    ) {
        try {
            // 1. In-app WebSocket notification
            val event = NotificationEvent.NewMessageReceived(
                messageId = messageId.value,
                conversationId = conversationId.value,
                senderDisplayName = senderDisplayName,
                senderUsername = senderUsername,
                contentPreview = contentPreview,
                timestamp = System.currentTimeMillis()
            )
            notificationRepository.notifyNewMessage(recipientId, event)

            // 2. Push notification (NoOp for now, swap to FCM later)
            pushNotificationService.sendPush(
                userId = recipientId,
                title = senderDisplayName,
                body = contentPreview,
                data = mapOf(
                    "type" to "new_message",
                    "conversationId" to conversationId.value,
                    "messageId" to messageId.value
                )
            )

            logger.info(
                "Notified new message: recipientId={}, messageId={}, conversationId={}",
                recipientId.value, messageId.value, conversationId.value
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to notify new message: recipientId={}, messageId={}",
                recipientId.value, messageId.value, e
            )
        }
    }

    suspend fun notifyMessagesRead(conversationId: ConversationId, readByUserId: UserId) {
        try {
            val conversation = messageRepository.findConversationById(conversationId) ?: return

            // Notify the other participant (the message sender)
            val recipientId = if (conversation.participant1Id == readByUserId)
                conversation.participant2Id else conversation.participant1Id

            val event = NotificationEvent.MessagesRead(
                conversationId = conversationId.value,
                readByUserId = readByUserId.value,
                timestamp = System.currentTimeMillis()
            )
            notificationRepository.notifyMessagesRead(recipientId, event)

            logger.info(
                "Notified messages read: recipientId={}, conversationId={}, readByUserId={}",
                recipientId.value, conversationId.value, readByUserId.value
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to notify messages read: conversationId={}", conversationId.value, e)
        }
    }

    suspend fun notifyMessageRecalled(messageId: MessageId) {
        try {
            val message = messageRepository.findMessageById(messageId) ?: return
            val conversation = messageRepository.findConversationById(message.conversationId) ?: return

            // Determine the other participant
            val recipientId = if (conversation.participant1Id == message.senderId)
                conversation.participant2Id else conversation.participant1Id

            val event = NotificationEvent.MessageRecalled(
                messageId = messageId.value,
                conversationId = message.conversationId.value,
                recalledByUserId = message.senderId.value,
                timestamp = System.currentTimeMillis()
            )
            notificationRepository.notifyMessageRecalled(recipientId, event)

            logger.info(
                "Notified message recalled: recipientId={}, messageId={}",
                recipientId.value, messageId.value
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to notify message recalled: messageId={}", messageId.value, e)
        }
    }
}
