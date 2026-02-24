package com.connor.domain.repository

import com.connor.domain.model.*
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    suspend fun findConversationPeerIds(userId: UserId): List<UserId>

    suspend fun findOrCreateConversation(userId1: UserId, userId2: UserId): Conversation

    suspend fun findConversationById(id: ConversationId): Conversation?

    fun findConversationsForUser(userId: UserId, limit: Int = 20, offset: Int = 0): Flow<ConversationDetail>

    suspend fun saveMessage(message: Message): Message

    fun findMessagesByConversation(conversationId: ConversationId, limit: Int = 50, offset: Int = 0): Flow<Message>

    suspend fun markConversationAsRead(conversationId: ConversationId, userId: UserId)

    suspend fun getUnreadCountForUser(userId: UserId): Int

    suspend fun findMessageById(messageId: MessageId): Message?

    suspend fun softDeleteMessage(messageId: MessageId): Boolean

    suspend fun recallMessage(messageId: MessageId): Boolean
}
