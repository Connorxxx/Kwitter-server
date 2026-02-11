package com.connor.domain.usecase

import com.connor.domain.model.ConversationDetail
import com.connor.domain.model.UserId
import com.connor.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow

class GetConversationsUseCase(
    private val messageRepository: MessageRepository
) {
    operator fun invoke(userId: UserId, limit: Int = 20, offset: Int = 0): Flow<ConversationDetail> {
        return messageRepository.findConversationsForUser(userId, limit, offset)
    }
}
