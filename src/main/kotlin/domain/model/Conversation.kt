package com.connor.domain.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.MessageError

@JvmInline
value class ConversationId(val value: Long)

@JvmInline
value class MessageId(val value: Long)

@JvmInline
value class MessageContent private constructor(val value: String) {
    companion object {
        private const val MAX_LENGTH = 2000

        operator fun invoke(value: String): Either<MessageError, MessageContent> {
            return when {
                value.isBlank() -> MessageError.EmptyContent.left()
                value.length > MAX_LENGTH -> MessageError.ContentTooLong(value.length, MAX_LENGTH).left()
                else -> MessageContent(value).right()
            }
        }

        fun unsafe(value: String): MessageContent = MessageContent(value)
    }
}

enum class DmPermission {
    EVERYONE,
    MUTUAL_FOLLOW
}

data class Conversation(
    val id: ConversationId,
    val participant1Id: UserId,
    val participant2Id: UserId,
    val lastMessageId: MessageId? = null,
    val lastMessageAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Canonical ordering: participant1Id < participant2Id lexicographically.
         * Ensures exactly one conversation per user pair.
         */
        fun canonicalParticipants(userId1: UserId, userId2: UserId): Pair<UserId, UserId> {
            return if (userId1.value < userId2.value) userId1 to userId2
            else userId2 to userId1
        }
    }
}

data class Message(
    val id: MessageId,
    val conversationId: ConversationId,
    val senderId: UserId,
    val content: MessageContent,
    val imageUrl: String? = null,
    val replyToMessageId: MessageId? = null,
    val readAt: Long? = null,
    val deletedAt: Long? = null,
    val recalledAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isDeleted: Boolean get() = deletedAt != null
    val isRecalled: Boolean get() = recalledAt != null
}

data class ConversationDetail(
    val conversation: Conversation,
    val otherUser: User,
    val lastMessage: Message?,
    val unreadCount: Int
)
