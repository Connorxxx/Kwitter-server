package com.connor.infrastructure.sse

import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import io.ktor.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SseConnectionManager {
    private val logger = LoggerFactory.getLogger(SseConnectionManager::class.java)

    data class SseConnection(
        val id: String,
        val userId: UserId,
        val channel: Channel<ServerSentEvent>
    )

    // connectionId → SseConnection
    private val connections = ConcurrentHashMap<String, SseConnection>()

    // userId → set of connectionIds
    private val userConnections = ConcurrentHashMap<UserId, MutableSet<String>>()

    // postId → set of userIds (user-level, not session-level)
    private val postSubscriptions = ConcurrentHashMap<PostId, MutableSet<UserId>>()

    // Monotonic event ID counter
    private val eventIdCounter = AtomicLong(0)

    fun nextEventId(): String = eventIdCounter.incrementAndGet().toString()

    fun registerConnection(userId: UserId): SseConnection {
        val connectionId = UUID.randomUUID().toString()
        val channel = Channel<ServerSentEvent>(capacity = Channel.BUFFERED)
        val connection = SseConnection(connectionId, userId, channel)

        connections[connectionId] = connection
        userConnections.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(connectionId)

        logger.info(
            "SSE connection registered: userId={}, connectionId={}, totalConnections={}",
            userId.value, connectionId, userConnections[userId]?.size ?: 0
        )
        return connection
    }

    fun removeConnection(connectionId: String) {
        val connection = connections.remove(connectionId) ?: return
        connection.channel.close()

        val userId = connection.userId
        userConnections[userId]?.remove(connectionId)
        if (userConnections[userId]?.isEmpty() == true) {
            userConnections.remove(userId)
            // Clean up post subscriptions for this user if they have no more connections
            postSubscriptions.values.forEach { subscribers -> subscribers.remove(userId) }
            postSubscriptions.entries.removeIf { it.value.isEmpty() }
        }

        logger.info(
            "SSE connection removed: userId={}, connectionId={}, remainingConnections={}",
            userId.value, connectionId, userConnections[userId]?.size ?: 0
        )
    }

    fun subscribeToPost(userId: UserId, postId: PostId) {
        postSubscriptions.computeIfAbsent(postId) { ConcurrentHashMap.newKeySet() }.add(userId)
        logger.info(
            "User subscribed to post: userId={}, postId={}, totalSubscribers={}",
            userId.value, postId.value, postSubscriptions[postId]?.size ?: 0
        )
    }

    fun unsubscribeFromPost(userId: UserId, postId: PostId) {
        postSubscriptions[postId]?.remove(userId)
        if (postSubscriptions[postId]?.isEmpty() == true) {
            postSubscriptions.remove(postId)
        }
        logger.info(
            "User unsubscribed from post: userId={}, postId={}, remainingSubscribers={}",
            userId.value, postId.value, postSubscriptions[postId]?.size ?: 0
        )
    }

    suspend fun sendToUser(userId: UserId, event: String, data: String) {
        val connectionIds = userConnections[userId] ?: return
        val sseEvent = ServerSentEvent(data = data, event = event, id = nextEventId())
        val staleIds = mutableListOf<String>()

        for (connId in connectionIds) {
            val conn = connections[connId] ?: continue
            try {
                conn.channel.send(sseEvent)
            } catch (_: ClosedSendChannelException) {
                staleIds.add(connId)
            } catch (e: Exception) {
                logger.error("Failed to send to connection: connectionId={}", connId, e)
                staleIds.add(connId)
            }
        }

        staleIds.forEach { removeConnection(it) }
    }

    suspend fun sendToUsers(userIds: Collection<UserId>, event: String, data: String) {
        val sseEvent = ServerSentEvent(data = data, event = event, id = nextEventId())
        val staleIds = mutableListOf<String>()

        for (userId in userIds) {
            val connectionIds = userConnections[userId] ?: continue
            for (connId in connectionIds) {
                val conn = connections[connId] ?: continue
                try {
                    conn.channel.send(sseEvent)
                } catch (_: ClosedSendChannelException) {
                    staleIds.add(connId)
                } catch (e: Exception) {
                    logger.error("Failed to send to connection: connectionId={}", connId, e)
                    staleIds.add(connId)
                }
            }
        }

        staleIds.forEach { removeConnection(it) }
    }

    suspend fun broadcastToAll(event: String, data: String) {
        val sseEvent = ServerSentEvent(data = data, event = event, id = nextEventId())
        var successCount = 0
        val staleIds = mutableListOf<String>()

        for ((connId, conn) in connections) {
            try {
                conn.channel.send(sseEvent)
                successCount++
            } catch (_: ClosedSendChannelException) {
                staleIds.add(connId)
            } catch (e: Exception) {
                logger.error("Failed to broadcast to connection: connectionId={}", connId, e)
                staleIds.add(connId)
            }
        }

        staleIds.forEach { removeConnection(it) }
        logger.info("Broadcasted to all: success={}, cleaned={}", successCount, staleIds.size)
    }

    suspend fun sendToPostSubscribers(postId: PostId, event: String, data: String) {
        val subscriberIds = postSubscriptions[postId] ?: return
        val sseEvent = ServerSentEvent(data = data, event = event, id = nextEventId())
        val staleIds = mutableListOf<String>()

        for (userId in subscriberIds) {
            val connectionIds = userConnections[userId] ?: continue
            for (connId in connectionIds) {
                val conn = connections[connId] ?: continue
                try {
                    conn.channel.send(sseEvent)
                } catch (_: ClosedSendChannelException) {
                    staleIds.add(connId)
                } catch (e: Exception) {
                    logger.error("Failed to send to post subscriber: connectionId={}", connId, e)
                    staleIds.add(connId)
                }
            }
        }

        staleIds.forEach { removeConnection(it) }
        logger.info(
            "Sent to post subscribers: postId={}, subscriberCount={}, cleaned={}",
            postId.value, subscriberIds.size, staleIds.size
        )
    }

    fun isUserOnline(userId: UserId): Boolean = userConnections.containsKey(userId)

    fun getOnlineStatus(userIds: Collection<UserId>): Map<UserId, Boolean> =
        userIds.associateWith { isUserOnline(it) }
}
