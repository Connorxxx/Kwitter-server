package com.connor.data.repository

import com.connor.data.db.dbQuery
import com.connor.data.db.mapping.toConversation
import com.connor.data.db.mapping.toMessage
import com.connor.data.db.schema.ConversationsTable
import com.connor.data.db.schema.MessagesTable
import com.connor.data.db.schema.UsersTable
import com.connor.data.db.mapping.toDomain
import com.connor.domain.model.*
import com.connor.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.slf4j.LoggerFactory
import java.util.*

class ExposedMessageRepository : MessageRepository {
    private val logger = LoggerFactory.getLogger(ExposedMessageRepository::class.java)

    override suspend fun findOrCreateConversation(userId1: UserId, userId2: UserId): Conversation = dbQuery {
        val (p1, p2) = Conversation.canonicalParticipants(userId1, userId2)

        // Try to find existing
        val existing = ConversationsTable.selectAll()
            .where {
                (ConversationsTable.participant1Id eq p1.value) and
                    (ConversationsTable.participant2Id eq p2.value)
            }
            .singleOrNull()
            ?.toConversation()

        if (existing != null) {
            logger.debug("Found existing conversation: id={}", existing.id.value)
            return@dbQuery existing
        }

        // Create new
        val conversationId = ConversationId(UUID.randomUUID().toString())
        val now = System.currentTimeMillis()

        ConversationsTable.insert {
            it[id] = conversationId.value
            it[participant1Id] = p1.value
            it[participant2Id] = p2.value
            it[lastMessageId] = null
            it[lastMessageAt] = null
            it[createdAt] = now
        }

        logger.info("Created conversation: id={}, p1={}, p2={}", conversationId.value, p1.value, p2.value)

        Conversation(
            id = conversationId,
            participant1Id = p1,
            participant2Id = p2,
            createdAt = now
        )
    }

    override suspend fun findConversationById(id: ConversationId): Conversation? = dbQuery {
        ConversationsTable.selectAll()
            .where { ConversationsTable.id eq id.value }
            .singleOrNull()
            ?.toConversation()
    }

    override fun findConversationsForUser(userId: UserId, limit: Int, offset: Int): Flow<ConversationDetail> = flow {
        val details = dbQuery {
            // Find conversations where user is participant, ordered by last message time
            val conversations = ConversationsTable.selectAll()
                .where {
                    (ConversationsTable.participant1Id eq userId.value) or
                        (ConversationsTable.participant2Id eq userId.value)
                }
                .orderBy(ConversationsTable.lastMessageAt to SortOrder.DESC_NULLS_LAST)
                .limit(limit + 1).offset(offset.toLong())
                .toList()

            conversations.map { row ->
                val conversation = row.toConversation()

                // Determine the other user
                val otherUserId = if (conversation.participant1Id == userId)
                    conversation.participant2Id else conversation.participant1Id

                // Fetch other user
                val otherUser = UsersTable.selectAll()
                    .where { UsersTable.id eq otherUserId.value }
                    .single()
                    .toDomain()

                // Fetch last message
                val lastMessage = conversation.lastMessageId?.let { msgId ->
                    MessagesTable.selectAll()
                        .where { MessagesTable.id eq msgId.value }
                        .singleOrNull()
                        ?.toMessage()
                }

                // Count unread messages (sent by other user, not yet read)
                val unreadCount = MessagesTable.selectAll()
                    .where {
                        (MessagesTable.conversationId eq conversation.id.value) and
                            (MessagesTable.senderId eq otherUserId.value) and
                            (MessagesTable.readAt.isNull())
                    }
                    .count().toInt()

                ConversationDetail(
                    conversation = conversation,
                    otherUser = otherUser,
                    lastMessage = lastMessage,
                    unreadCount = unreadCount
                )
            }
        }

        details.forEach { emit(it) }
    }

    override suspend fun saveMessage(message: Message): Message = dbQuery {
        MessagesTable.insert {
            it[id] = message.id.value
            it[conversationId] = message.conversationId.value
            it[senderId] = message.senderId.value
            it[content] = message.content.value
            it[imageUrl] = message.imageUrl
            it[readAt] = message.readAt
            it[createdAt] = message.createdAt
        }

        // Update conversation's last message
        ConversationsTable.update({ ConversationsTable.id eq message.conversationId.value }) {
            it[lastMessageId] = message.id.value
            it[lastMessageAt] = message.createdAt
        }

        logger.info("Saved message: id={}, conversationId={}", message.id.value, message.conversationId.value)
        message
    }

    override fun findMessagesByConversation(
        conversationId: ConversationId,
        limit: Int,
        offset: Int
    ): Flow<Message> = flow {
        val messages = dbQuery {
            MessagesTable.selectAll()
                .where { MessagesTable.conversationId eq conversationId.value }
                .orderBy(MessagesTable.createdAt to SortOrder.DESC)
                .limit(limit + 1).offset(offset.toLong())
                .map { it.toMessage() }
        }

        messages.forEach { emit(it) }
    }

    override suspend fun markConversationAsRead(conversationId: ConversationId, userId: UserId) = dbQuery {
        val now = System.currentTimeMillis()

        // Mark all unread messages from the other user as read
        val updated = MessagesTable.update({
            (MessagesTable.conversationId eq conversationId.value) and
                (MessagesTable.senderId neq userId.value) and
                (MessagesTable.readAt.isNull())
        }) {
            it[readAt] = now
        }

        logger.info("Marked {} messages as read: conversationId={}, userId={}", updated, conversationId.value, userId.value)
    }

    override suspend fun getUnreadCountForUser(userId: UserId): Int = dbQuery {
        // Get all conversations for user, then count unread messages from other participants
        val conversationIds = ConversationsTable.selectAll()
            .where {
                (ConversationsTable.participant1Id eq userId.value) or
                    (ConversationsTable.participant2Id eq userId.value)
            }
            .map { it[ConversationsTable.id] }

        if (conversationIds.isEmpty()) return@dbQuery 0

        MessagesTable.selectAll()
            .where {
                (MessagesTable.conversationId inList conversationIds) and
                    (MessagesTable.senderId neq userId.value) and
                    (MessagesTable.readAt.isNull())
            }
            .count().toInt()
    }
}
