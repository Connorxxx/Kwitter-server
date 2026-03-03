package com.connor.features.notification

import com.connor.core.security.UserPrincipal
import com.connor.domain.model.UserId
import com.connor.domain.repository.MessageRepository
import com.connor.domain.repository.NotificationRepository
import com.connor.infrastructure.sse.SseConnectionManager
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("NotificationSse")
private val presenceJson = Json { encodeDefaults = true }

@Serializable
private data class PresenceUserState(
    val userId: Long,
    val isOnline: Boolean,
    val timestamp: Long
)

@Serializable
private data class PresenceSnapshotPayload(val users: List<PresenceUserState>)

@Serializable
private data class ConnectedPayload(val userId: Long)

fun Route.notificationSse(
    connectionManager: SseConnectionManager,
    notificationRepository: NotificationRepository,
    messageRepository: MessageRepository
) {
    authenticate("auth-jwt") {
        sse("/v1/notifications/stream") {
            val principal = call.principal<UserPrincipal>()

            if (principal == null) {
                logger.warn("SSE connection attempt without valid authentication")
                close()
                return@sse
            }

            val userId = UserId(principal.userId)

            // Detect if this is the first session (online broadcast only on 0→1)
            val isFirstSession = !connectionManager.isUserOnline(userId)
            val connection = connectionManager.registerConnection(userId)
            logger.info("SSE connected: userId={}, firstSession={}", userId.value, isFirstSession)

            try {
                // 1. Confirm connection
                send(ServerSentEvent(
                    data = presenceJson.encodeToString(ConnectedPayload(userId.value)),
                    event = "connected",
                    id = connectionManager.nextEventId()
                ))

                // 2. Query conversation peers
                val peerIds = try {
                    messageRepository.findConversationPeerIds(userId)
                } catch (e: Exception) {
                    logger.error("Failed to query peers for userId={}, degrading to empty snapshot", userId.value, e)
                    emptyList()
                }

                // 3. Send presence snapshot
                val now = System.currentTimeMillis()
                val onlineStatus = connectionManager.getOnlineStatus(peerIds)
                val snapshotPayload = PresenceSnapshotPayload(
                    users = onlineStatus.map { (uid, online) ->
                        PresenceUserState(userId = uid.value, isOnline = online, timestamp = now)
                    }
                )
                send(ServerSentEvent(
                    data = presenceJson.encodeToString(snapshotPayload),
                    event = "presence_snapshot",
                    id = connectionManager.nextEventId()
                ))
                logger.info(
                    "presence_snapshot_sent: userId={}, peerCount={}, onlineCount={}",
                    userId.value, peerIds.size, onlineStatus.count { it.value }
                )

                // 4. Broadcast online to peers (only on first session)
                if (isFirstSession && peerIds.isNotEmpty()) {
                    val changedData = presenceJson.encodeToString(
                        PresenceUserState(userId = userId.value, isOnline = true, timestamp = now)
                    )
                    connectionManager.sendToUsers(peerIds, "user_presence_changed", changedData)
                    logger.info("presence_online_broadcast: userId={}, peerCount={}", userId.value, peerIds.size)
                }

                // 5. Heartbeat job (comment-only keepalive every 30s)
                val heartbeatJob = launch {
                    while (isActive) {
                        delay(30_000L)
                        try {
                            send(ServerSentEvent(comments = "heartbeat"))
                        } catch (_: Exception) {
                            break
                        }
                    }
                }

                // 6. Read from channel and forward to SSE stream
                try {
                    for (event in connection.channel) {
                        send(event)
                    }
                } finally {
                    heartbeatJob.cancel()
                }
            } catch (e: CancellationException) {
                logger.debug("SSE cancelled: userId={}", userId.value)
                throw e
            } catch (e: Exception) {
                logger.error("SSE error for user ${userId.value}", e)
            } finally {
                connectionManager.removeConnection(connection.id)

                // Last session disconnect → broadcast offline to peers
                if (!connectionManager.isUserOnline(userId)) {
                    try {
                        val peerIds = messageRepository.findConversationPeerIds(userId)
                        if (peerIds.isNotEmpty()) {
                            val changedData = presenceJson.encodeToString(
                                PresenceUserState(
                                    userId = userId.value,
                                    isOnline = false,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                            connectionManager.sendToUsers(peerIds, "user_presence_changed", changedData)
                            logger.info("presence_offline_broadcast: userId={}, peerCount={}", userId.value, peerIds.size)
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to broadcast offline: userId={}", userId.value, e)
                    }
                }

                logger.info("SSE disconnected: userId={}", userId.value)
            }
        }
    }
}
