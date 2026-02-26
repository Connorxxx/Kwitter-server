package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.MessageError
import com.connor.domain.model.ConversationId
import com.connor.domain.model.Message
import com.connor.domain.model.MessageId
import com.connor.domain.model.UserId
import com.connor.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow

class GetMessagesUseCase(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(
        conversationId: ConversationId,
        userId: UserId,
        limit: Int = 50,
        offset: Int = 0,
        beforeId: MessageId? = null
    ): Either<MessageError, Flow<Message>> {
        // Verify user is participant
        val conversation = messageRepository.findConversationById(conversationId)
            ?: return MessageError.ConversationNotFound(conversationId).left()

        if (conversation.participant1Id != userId && conversation.participant2Id != userId) {
            return MessageError.NotConversationParticipant(userId, conversationId).left()
        }

        return messageRepository.findMessagesByConversation(conversationId, limit, offset, beforeId).right()
    }
}
