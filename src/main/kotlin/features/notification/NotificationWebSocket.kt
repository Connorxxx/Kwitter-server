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
private val snapshotJsonCodec = Json { encodeDefaults = true }

@Serializable
private data class PresenceSnapshotMessage(val type: String, val data: PresenceSnapshotData)

@Serializable
private data class PresenceSnapshotData(val users: List<PresenceSnapshotUser>)

@Serializable
private data class PresenceSnapshotUser(val userId: String, val isOnline: Boolean, val timestamp: Long)

/**
 * WebSocket 通知端点
 *
 * 端点：/v1/notifications/ws
 * 认证：需要 JWT Token
 *
 * 功能：
 * 1. 建立 WebSocket 连接
 * 2. 处理客户端订阅消息
 * 3. 推送实时通知（含打字状态、在线状态）
 * 4. 自动清理断开的连接
 */
fun Route.notificationWebSocket(
    connectionManager: WebSocketConnectionManager,
    notificationRepository: NotificationRepository,
    messageRepository: com.connor.domain.repository.MessageRepository
) {
    authenticate("auth-jwt") {
        webSocket("/v1/notifications/ws") {
            // 获取当前认证用户
            val principal = call.principal<UserPrincipal>()

            if (principal == null) {
                logger.warn("WebSocket connection attempt without valid authentication")
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            val userId = UserId(principal.userId)

            // 注册用户连接
            connectionManager.addUserSession(userId, this)
            logger.info("WebSocket connected: userId={}", userId.value)

            try {
                // 发送连接成功消息
                send(Frame.Text("""{"type":"connected","userId":"${userId.value}"}"""))

                // 发送在线状态快照（修复：后连接用户看不到先连接用户在线的问题）
                try {
                    val peerIds = messageRepository.findConversationPeerIds(userId)
                    if (peerIds.isNotEmpty()) {
                        val onlineStatus = connectionManager.getOnlineStatus(peerIds)
                        val now = System.currentTimeMillis()
                        val snapshotUsers = onlineStatus.map { (uid, online) ->
                            PresenceSnapshotUser(
                                userId = uid.value,
                                isOnline = online,
                                timestamp = now
                            )
                        }
                        val snapshotJson = snapshotJsonCodec.encodeToString(
                            PresenceSnapshotMessage(
                                type = "presence_snapshot",
                                data = PresenceSnapshotData(users = snapshotUsers)
                            )
                        )
                        send(Frame.Text(snapshotJson))
                        logger.info(
                            "presence_snapshot_sent: userId={}, snapshotSize={}, conversationPeerCount={}",
                            userId.value, snapshotUsers.count { it.isOnline }, peerIds.size
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Failed to send presence snapshot for user {}", userId.value, e)
                }

                // 广播上线状态
                try {
                    notificationRepository.notifyUserPresenceChanged(
                        userId,
                        NotificationEvent.UserPresenceChanged(
                            userId = userId.value,
                            isOnline = true,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    logger.error("Failed to broadcast online status for user {}", userId.value, e)
                }

                // 处理客户端消息
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
                // 重新抛出取消异常，保持协程取消语义
                logger.debug("WebSocket cancelled: userId={}", userId.value)
                throw e
            } catch (e: Exception) {
                logger.error("WebSocket error for user ${userId.value}", e)
            } finally {
                // 清理连接和所有订阅
                connectionManager.removeUserSession(this)

                // 仅当用户所有会话都断开时才广播下线
                if (!connectionManager.isUserOnline(userId)) {
                    try {
                        notificationRepository.notifyUserPresenceChanged(
                            userId,
                            NotificationEvent.UserPresenceChanged(
                                userId = userId.value,
                                isOnline = false,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    } catch (e: Exception) {
                        logger.error("Failed to broadcast offline status for user {}", userId.value, e)
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

                connectionManager.subscribeToPost(userId, PostId(postId), session)
                session.send(Frame.Text("""{"type":"subscribed","postId":"$postId"}"""))
                logger.debug("User subscribed to post: userId={}, postId={}", userId.value, postId)
            }

            "unsubscribe_post" -> {
                val postId = message.postId
                if (postId == null) {
                    session.send(Frame.Text("""{"type":"error","message":"Missing postId"}"""))
                    return
                }

                connectionManager.unsubscribeFromPost(PostId(postId), session)
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
                val convId = com.connor.domain.model.ConversationId(conversationId)
                val conversation = messageRepository.findConversationById(convId)
                if (conversation == null) {
                    session.send(Frame.Text("""{"type":"error","message":"Conversation not found"}"""))
                    return
                }

                // Determine the partner
                val partnerId = if (conversation.participant1Id == userId)
                    conversation.participant2Id else conversation.participant1Id

                val event = NotificationEvent.TypingIndicator(
                    conversationId = conversationId,
                    userId = userId.value,
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
        // 重新抛出取消异常
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
