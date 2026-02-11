package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.MessageError
import com.connor.domain.model.ConversationId
import com.connor.domain.model.UserId
import com.connor.domain.repository.MessageRepository

class MarkConversationReadUseCase(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(
        conversationId: ConversationId,
        userId: UserId
    ): Either<MessageError, Unit> {
        val conversation = messageRepository.findConversationById(conversationId)
            ?: return MessageError.ConversationNotFound(conversationId).left()

        if (conversation.participant1Id != userId && conversation.participant2Id != userId) {
            return MessageError.NotConversationParticipant(userId, conversationId).left()
        }

        messageRepository.markConversationAsRead(conversationId, userId)
        return Unit.right()
    }
}
