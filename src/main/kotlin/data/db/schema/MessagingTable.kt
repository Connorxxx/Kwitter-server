package com.connor.data.db.schema

import org.jetbrains.exposed.v1.core.Table

object ConversationsTable : Table("conversations") {
    val id = varchar("id", 36)
    val participant1Id = varchar("participant1_id", 36).references(UsersTable.id)
    val participant2Id = varchar("participant2_id", 36).references(UsersTable.id)
    val lastMessageId = varchar("last_message_id", 36).nullable()
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
    val id = varchar("id", 36)
    val conversationId = varchar("conversation_id", 36).references(ConversationsTable.id)
    val senderId = varchar("sender_id", 36).references(UsersTable.id)
    val content = varchar("content", 2000)
    val imageUrl = varchar("image_url", 256).nullable()
    val readAt = long("read_at").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_messages_conversation_created", isUnique = false, conversationId, createdAt)
        index("idx_messages_conversation_read", isUnique = false, conversationId, senderId, readAt)
    }
}
