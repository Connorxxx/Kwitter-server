package com.connor.domain.usecase

import com.connor.domain.model.*
import com.connor.domain.repository.NotificationRepository
import com.connor.domain.service.PushNotificationService
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

class NotifyNewMessageUseCase(
    private val notificationRepository: NotificationRepository,
    private val pushNotificationService: PushNotificationService
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
}
