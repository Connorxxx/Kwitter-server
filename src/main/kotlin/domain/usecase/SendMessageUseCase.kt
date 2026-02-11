package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.connor.domain.failure.MessageError
import com.connor.domain.model.*
import com.connor.domain.repository.MessageRepository
import com.connor.domain.repository.UserRepository
import org.slf4j.LoggerFactory
import java.util.*

data class SendMessageCommand(
    val senderId: UserId,
    val recipientId: UserId,
    val content: String,
    val imageUrl: String? = null
)

data class SendMessageResult(
    val message: Message,
    val conversation: Conversation
)

class SendMessageUseCase(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(SendMessageUseCase::class.java)

    suspend operator fun invoke(cmd: SendMessageCommand): Either<MessageError, SendMessageResult> {
        logger.info("Sending message: senderId={}, recipientId={}", cmd.senderId.value, cmd.recipientId.value)

        return either {
            // 1. Cannot message self
            ensure(cmd.senderId.value != cmd.recipientId.value) {
                MessageError.CannotMessageSelf
            }

            // 2. Validate recipient exists
            val recipient = userRepository.findById(cmd.recipientId).fold(
                ifLeft = { raise(MessageError.RecipientNotFound(cmd.recipientId)) },
                ifRight = { it }
            )

            // 3. Check DM permission (future: check dmPermission field on recipient)
            // For now, everyone can DM. When dmPermission = MUTUAL_FOLLOW:
            // val isMutualFollow = userRepository.isFollowing(cmd.senderId, cmd.recipientId)
            //     && userRepository.isFollowing(cmd.recipientId, cmd.senderId)
            // ensure(isMutualFollow) { MessageError.DmPermissionDenied }

            // 4. Validate content
            val content = MessageContent(cmd.content).bind()

            // 5. Find or create conversation
            val conversation = messageRepository.findOrCreateConversation(cmd.senderId, cmd.recipientId)

            // 6. Save message
            val message = Message(
                id = MessageId(UUID.randomUUID().toString()),
                conversationId = conversation.id,
                senderId = cmd.senderId,
                content = content,
                imageUrl = cmd.imageUrl
            )

            val savedMessage = messageRepository.saveMessage(message)

            logger.info(
                "Message sent: messageId={}, conversationId={}",
                savedMessage.id.value, conversation.id.value
            )

            SendMessageResult(message = savedMessage, conversation = conversation)
        }
    }
}
