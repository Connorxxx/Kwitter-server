package com.connor.domain.failure

import com.connor.domain.model.ConversationId
import com.connor.domain.model.MessageId
import com.connor.domain.model.UserId

sealed interface MessageError {
    data object EmptyContent : MessageError
    data class ContentTooLong(val actual: Int, val max: Int) : MessageError
    data class ConversationNotFound(val conversationId: ConversationId) : MessageError
    data class MessageNotFound(val messageId: MessageId) : MessageError
    data class NotConversationParticipant(val userId: UserId, val conversationId: ConversationId) : MessageError
    data object CannotMessageSelf : MessageError
    data class RecipientNotFound(val recipientId: UserId) : MessageError
    data object DmPermissionDenied : MessageError
    data class UserBlocked(val userId: UserId) : MessageError
    data object NotMessageSender : MessageError
    data object RecallTimeExpired : MessageError
    data object MessageAlreadyRecalled : MessageError
    data object MessageAlreadyDeleted : MessageError
}
