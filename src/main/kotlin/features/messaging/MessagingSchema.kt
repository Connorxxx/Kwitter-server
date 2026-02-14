package com.connor.features.messaging

import kotlinx.serialization.Serializable

// ========== Request DTOs ==========

@Serializable
data class SendMessageRequest(
    val recipientId: String,
    val content: String,
    val imageUrl: String? = null,
    val replyToMessageId: String? = null
)

// ========== Response DTOs ==========

@Serializable
data class ConversationResponse(
    val id: String,
    val otherUser: ConversationUserDto,
    val lastMessage: MessageResponse?,
    val unreadCount: Int,
    val createdAt: Long
)

@Serializable
data class ConversationUserDto(
    val id: String,
    val displayName: String,
    val username: String,
    val avatarUrl: String? = null
)

@Serializable
data class MessageResponse(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val content: String,
    val imageUrl: String?,
    val replyToMessageId: String?,
    val readAt: Long?,
    val deletedAt: Long?,
    val recalledAt: Long?,
    val createdAt: Long
)

@Serializable
data class ConversationListResponse(
    val conversations: List<ConversationResponse>,
    val hasMore: Boolean
)

@Serializable
data class MessageListResponse(
    val messages: List<MessageResponse>,
    val hasMore: Boolean
)

@Serializable
data class MarkReadResponse(
    val conversationId: String,
    val readAt: Long
)
