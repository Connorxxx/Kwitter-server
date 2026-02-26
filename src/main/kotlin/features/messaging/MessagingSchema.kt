package com.connor.features.messaging

import kotlinx.serialization.Serializable

// ========== Request DTOs ==========

@Serializable
data class SendMessageRequest(
    val recipientId: Long,
    val content: String,
    val imageUrl: String? = null,
    val replyToMessageId: Long? = null
)

// ========== Response DTOs ==========

@Serializable
data class ConversationResponse(
    val id: Long,
    val otherUser: ConversationUserDto,
    val lastMessage: MessageResponse?,
    val unreadCount: Int,
    val createdAt: Long
)

@Serializable
data class ConversationUserDto(
    val id: Long,
    val displayName: String,
    val username: String,
    val avatarUrl: String? = null
)

@Serializable
data class MessageResponse(
    val id: Long,
    val conversationId: Long,
    val senderId: Long,
    val content: String,
    val imageUrl: String?,
    val replyToMessageId: Long?,
    val readAt: Long?,
    val deletedAt: Long?,
    val recalledAt: Long?,
    val createdAt: Long
)

@Serializable
data class ConversationListResponse(
    val conversations: List<ConversationResponse>,
    val hasMore: Boolean,
    val nextCursor: Long? = null
)

@Serializable
data class MessageListResponse(
    val messages: List<MessageResponse>,
    val hasMore: Boolean,
    val nextCursor: Long? = null
)

@Serializable
data class MarkReadResponse(
    val conversationId: Long,
    val readAt: Long
)
