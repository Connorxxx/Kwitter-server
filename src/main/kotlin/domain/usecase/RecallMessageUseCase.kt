package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.connor.domain.failure.MessageError
import com.connor.domain.model.MessageId
import com.connor.domain.model.UserId
import com.connor.domain.repository.MessageRepository

class RecallMessageUseCase(
    private val messageRepository: MessageRepository
) {
    companion object {
        private const val RECALL_TIME_LIMIT_MS = 3 * 60 * 1000L // 3 minutes
    }

    suspend operator fun invoke(
        messageId: MessageId,
        userId: UserId
    ): Either<MessageError, Unit> = either {
        val message = messageRepository.findMessageById(messageId)
            ?: raise(MessageError.MessageNotFound(messageId))

        // Only sender can recall
        ensure(message.senderId == userId) {
            MessageError.NotMessageSender
        }

        ensure(!message.isRecalled) {
            MessageError.MessageAlreadyRecalled
        }

        ensure(!message.isDeleted) {
            MessageError.MessageAlreadyDeleted
        }

        // Check 3-minute time limit
        val elapsed = System.currentTimeMillis() - message.createdAt
        ensure(elapsed <= RECALL_TIME_LIMIT_MS) {
            MessageError.RecallTimeExpired
        }

        messageRepository.recallMessage(messageId)
    }
}
