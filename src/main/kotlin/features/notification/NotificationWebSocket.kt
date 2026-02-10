package com.connor.features.notification

import com.connor.core.security.UserPrincipal
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.infrastructure.websocket.WebSocketConnectionManager
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("NotificationWebSocket")

/**
 * WebSocket 通知端点
 *
 * 端点：/v1/notifications/ws
 * 认证：需要 JWT Token
 *
 * 功能：
 * 1. 建立 WebSocket 连接
 * 2. 处理客户端订阅消息
 * 3. 推送实时通知
 * 4. 自动清理断开的连接
 */
fun Route.notificationWebSocket(
    connectionManager: WebSocketConnectionManager
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

                // 处理客户端消息
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            handleClientMessage(text, userId, this, connectionManager)
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
    connectionManager: WebSocketConnectionManager
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
