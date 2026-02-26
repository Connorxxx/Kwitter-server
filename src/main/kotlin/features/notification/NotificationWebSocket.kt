package com.connor.features.notification

import com.connor.core.security.UserPrincipal
import com.connor.domain.model.NotificationEvent
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.repository.NotificationRepository
import com.connor.infrastructure.websocket.WebSocketConnectionManager
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("NotificationWebSocket")
private val presenceJson = Json { encodeDefaults = true }

// ---- Presence wire-format DTOs (transport layer only) ----

@Serializable
private data class PresenceUserState(
    val userId: String,
    val isOnline: Boolean,
    val timestamp: Long
)

@Serializable
private data class PresenceSnapshotPayload(val users: List<PresenceUserState>)

@Serializable
private data class PresenceSnapshotMessage(
    val type: String = "presence_snapshot",
    val data: PresenceSnapshotPayload
)

@Serializable
private data class PresenceChangedMessage(
    val type: String = "user_presence_changed",
    val data: PresenceUserState
)

/**
 * WebSocket 通知端点
 *
 * 端点：/v1/notifications/ws
 * 认证：需要 JWT Token
 *
 * 功能：
 * 1. 建立 WebSocket 连接
 * 2. Presence 协议：快照 + 增量（定向推送给对话对端）
 * 3. 推送实时通知（打字状态等）
 * 4. 处理客户端订阅消息
 * 5. 自动清理断开的连接
 */
fun Route.notificationWebSocket(
    connectionManager: WebSocketConnectionManager,
    notificationRepository: NotificationRepository,
    messageRepository: com.connor.domain.repository.MessageRepository
) {
    authenticate("auth-jwt") {
        webSocket("/v1/notifications/ws") {
            val principal = call.principal<UserPrincipal>()

            if (principal == null) {
                logger.warn("WebSocket connection attempt without valid authentication")
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            val userId = UserId(principal.userId.toLong())

            // 检测是否为首个会话（上线广播仅在 0→1 时触发，与下线的 1→0 对称）
            val isFirstSession = !connectionManager.isUserOnline(userId)
            connectionManager.addUserSession(userId, this)
            logger.info("WebSocket connected: userId={}, firstSession={}", userId.value, isFirstSession)

            try {
                // 1. 确认连接
                send(Frame.Text("""{"type":"connected","userId":"${userId.value}"}"""))

                // 2. 查询对话对端（snapshot 和 broadcast 共享同一组 peerIds）
                val peerIds = try {
                    messageRepository.findConversationPeerIds(userId)
                } catch (e: Exception) {
                    logger.error("Failed to query peers for userId={}, degrading to empty snapshot", userId.value, e)
                    emptyList()
                }

                // 3. 发送 presence_snapshot（契约保证：每次连接必发，允许 users=[]）
                val now = System.currentTimeMillis()
                val onlineStatus = connectionManager.getOnlineStatus(peerIds)
                val snapshot = PresenceSnapshotMessage(
                    data = PresenceSnapshotPayload(
                        users = onlineStatus.map { (uid, online) ->
                            PresenceUserState(userId = uid.value.toString(), isOnline = online, timestamp = now)
                        }
                    )
                )
                send(Frame.Text(presenceJson.encodeToString(snapshot)))
                logger.info(
                    "presence_snapshot_sent: userId={}, peerCount={}, onlineCount={}",
                    userId.value, peerIds.size, onlineStatus.count { it.value }
                )

                // 4. 通知对端本用户上线（仅首个会话，定向推送给对话对端）
                if (isFirstSession && peerIds.isNotEmpty()) {
                    val changedMsg = presenceJson.encodeToString(
                        PresenceChangedMessage(
                            data = PresenceUserState(userId = userId.value.toString(), isOnline = true, timestamp = now)
                        )
                    )
                    connectionManager.sendToUsers(peerIds, changedMsg)
                    logger.info(
                        "presence_online_broadcast: userId={}, peerCount={}",
                        userId.value, peerIds.size
                    )
                }

                // 5. 消息循环
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            handleClientMessage(text, userId, this, connectionManager, notificationRepository, messageRepository)
                        }
                        is Frame.Close -> {
                            logger.info("WebSocket close frame received: userId={}", userId.value)
                            break
                        }
                        else -> {
                            logger.debug("Received non-text frame: userId={}, frameType={}", userId.value, frame.frameType)
                        }
                    }
                }
            } catch (e: CancellationException) {
                logger.debug("WebSocket cancelled: userId={}", userId.value)
                throw e
            } catch (e: Exception) {
                logger.error("WebSocket error for user ${userId.value}", e)
            } finally {
                connectionManager.removeUserSession(this)

                // 最后一个会话断开 → 通知对端下线（与上线的 isFirstSession 对称）
                if (!connectionManager.isUserOnline(userId)) {
                    try {
                        val peerIds = messageRepository.findConversationPeerIds(userId)
                        if (peerIds.isNotEmpty()) {
                            val changedMsg = presenceJson.encodeToString(
                                PresenceChangedMessage(
                                    data = PresenceUserState(
                                        userId = userId.value.toString(),
                                        isOnline = false,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                            )
                            connectionManager.sendToUsers(peerIds, changedMsg)
                            logger.info(
                                "presence_offline_broadcast: userId={}, peerCount={}",
                                userId.value, peerIds.size
                            )
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to broadcast offline: userId={}", userId.value, e)
                    }
                }

                logger.info("WebSocket disconnected: userId={}", userId.value)
            }
        }
    }
}

/**
 * 处理客户端发送的消息
 *
 * 支持的消息类型：
 * - subscribe_post: 订阅 Post 更新
 * - unsubscribe_post: 取消订阅 Post
 * - typing / stop_typing: 打字状态
 * - ping: 心跳保活
 */
private suspend fun handleClientMessage(
    text: String,
    userId: UserId,
    session: DefaultWebSocketServerSession,
    connectionManager: WebSocketConnectionManager,
    notificationRepository: NotificationRepository,
    messageRepository: com.connor.domain.repository.MessageRepository
) {
    try {
        val json = Json { ignoreUnknownKeys = true }
        val message = json.decodeFromString<WebSocketClientMessageDto>(text)

        when (message.type) {
            "subscribe_post" -> {
                val postId = message.postId
                if (postId == null) {
                    session.send(Frame.Text("""{"type":"error","message":"Missing postId"}"""))
                    return
                }

                connectionManager.subscribeToPost(userId, PostId(postId.toLong()), session)
                session.send(Frame.Text("""{"type":"subscribed","postId":"$postId"}"""))
                logger.debug("User subscribed to post: userId={}, postId={}", userId.value, postId)
            }

            "unsubscribe_post" -> {
                val postId = message.postId
                if (postId == null) {
                    session.send(Frame.Text("""{"type":"error","message":"Missing postId"}"""))
                    return
                }

                connectionManager.unsubscribeFromPost(PostId(postId.toLong()), session)
                session.send(Frame.Text("""{"type":"unsubscribed","postId":"$postId"}"""))
                logger.debug("User unsubscribed from post: userId={}, postId={}", userId.value, postId)
            }

            "typing", "stop_typing" -> {
                val conversationId = message.conversationId
                if (conversationId == null) {
                    session.send(Frame.Text("""{"type":"error","message":"Missing conversationId"}"""))
                    return
                }

                val isTyping = message.type == "typing"
                val convId = com.connor.domain.model.ConversationId(conversationId.toLong())
                val conversation = messageRepository.findConversationById(convId)
                if (conversation == null) {
                    session.send(Frame.Text("""{"type":"error","message":"Conversation not found"}"""))
                    return
                }

                val partnerId = if (conversation.participant1Id == userId)
                    conversation.participant2Id else conversation.participant1Id

                val event = NotificationEvent.TypingIndicator(
                    conversationId = conversationId,
                    userId = userId.value.toString(),
                    isTyping = isTyping,
                    timestamp = System.currentTimeMillis()
                )

                notificationRepository.notifyTypingIndicator(partnerId, event)

                logger.trace("Typing indicator: userId={}, conversationId={}, isTyping={}", userId.value, conversationId, isTyping)
            }

            "ping" -> {
                session.send(Frame.Text("""{"type":"pong"}"""))
                logger.trace("Ping-pong: userId={}", userId.value)
            }

            else -> {
                logger.warn("Unknown message type: {}, userId={}", message.type, userId.value)
                session.send(Frame.Text("""{"type":"error","message":"Unknown message type"}"""))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Failed to parse or handle client message: userId={}", userId.value, e)
        try {
            session.send(Frame.Text("""{"type":"error","message":"Invalid message format"}"""))
        } catch (sendError: Exception) {
            logger.error("Failed to send error message to client", sendError)
        }
    }
}
