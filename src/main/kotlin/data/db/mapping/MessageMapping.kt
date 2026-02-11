package com.connor.data.db.mapping

import com.connor.data.db.schema.ConversationsTable
import com.connor.data.db.schema.MessagesTable
import com.connor.domain.model.*
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toConversation(): Conversation {
    return Conversation(
        id = ConversationId(this[ConversationsTable.id]),
        participant1Id = UserId(this[ConversationsTable.participant1Id]),
        participant2Id = UserId(this[ConversationsTable.participant2Id]),
        lastMessageId = this[ConversationsTable.lastMessageId]?.let { MessageId(it) },
        lastMessageAt = this[ConversationsTable.lastMessageAt],
        createdAt = this[ConversationsTable.createdAt]
    )
}

fun ResultRow.toMessage(): Message {
    return Message(
        id = MessageId(this[MessagesTable.id]),
        conversationId = ConversationId(this[MessagesTable.conversationId]),
        senderId = UserId(this[MessagesTable.senderId]),
        content = MessageContent.unsafe(this[MessagesTable.content]),
        imageUrl = this[MessagesTable.imageUrl],
        readAt = this[MessagesTable.readAt],
        createdAt = this[MessagesTable.createdAt]
    )
}
