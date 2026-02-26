package com.connor.data.db.schema

import org.jetbrains.exposed.v1.core.Table

object ConversationsTable : Table("conversations") {
    val id = long("id")
    val participant1Id = long("participant1_id").references(UsersTable.id)
    val participant2Id = long("participant2_id").references(UsersTable.id)
    val lastMessageId = long("last_message_id").nullable()
    val lastMessageAt = long("last_message_at").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uk_conversation_participants", participant1Id, participant2Id)
        index("idx_conversations_p1_last", isUnique = false, participant1Id, lastMessageAt)
        index("idx_conversations_p2_last", isUnique = false, participant2Id, lastMessageAt)
    }
}

object MessagesTable : Table("messages") {
    val id = long("id")
    val conversationId = long("conversation_id").references(ConversationsTable.id)
    val senderId = long("sender_id").references(UsersTable.id)
    val content = varchar("content", 2000)
    val imageUrl = varchar("image_url", 256).nullable()
    val replyToMessageId = long("reply_to_message_id").nullable()
    val readAt = long("read_at").nullable()
    val deletedAt = long("deleted_at").nullable()
    val recalledAt = long("recalled_at").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_messages_conversation_created", isUnique = false, conversationId, createdAt)
        index("idx_messages_conversation_read", isUnique = false, conversationId, senderId, readAt)
    }
}
