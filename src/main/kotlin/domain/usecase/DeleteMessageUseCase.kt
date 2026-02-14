package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.connor.domain.failure.MessageError
import com.connor.domain.model.ConversationId
import com.connor.domain.model.MessageId
import com.connor.domain.model.UserId
import com.connor.domain.repository.MessageRepository

class DeleteMessageUseCase(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(
        messageId: MessageId,
        userId: UserId
    ): Either<MessageError, Unit> = either {
        val message = messageRepository.findMessageById(messageId)
            ?: raise(MessageError.MessageNotFound(messageId))

        // Verify user is participant of the conversation
        val conversation = messageRepository.findConversationById(message.conversationId)
            ?: raise(MessageError.ConversationNotFound(message.conversationId))

        ensure(conversation.participant1Id == userId || conversation.participant2Id == userId) {
            MessageError.NotConversationParticipant(userId, message.conversationId)
        }

        // Only sender can delete their own message
        ensure(message.senderId == userId) {
            MessageError.NotMessageSender
        }

        ensure(!message.isDeleted) {
            MessageError.MessageAlreadyDeleted
        }

        messageRepository.softDeleteMessage(messageId)
    }
}
