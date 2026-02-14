package com.connor.infrastructure.websocket

import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket 连接管理器
 *
 * 职责：
 * 1. 管理所有活跃的 WebSocket 连接
 * 2. 维护用户到会话的映射（一个用户可能有多个设备连接）
 * 3. 维护 Post 订阅关系（哪些用户订阅了哪些 Post）
 * 4. 提供广播和定向推送方法
 * 5. 自动清理断开的连接
 *
 * 线程安全：使用 ConcurrentHashMap 保证并发安全
 */
class WebSocketConnectionManager {
    private val logger = LoggerFactory.getLogger(WebSocketConnectionManager::class.java)

    // 用户连接映射：UserId -> Set<WebSocketSession>
    // 一个用户可能从多个设备连接（手机、电脑等）
    private val userSessions = ConcurrentHashMap<UserId, MutableSet<DefaultWebSocketSession>>()

    // Post 订阅映射：PostId -> Set<WebSocketSession>
    // 记录哪些会话订阅了哪个 Post（用户正在查看该 Post 详情页）
    private val postSubscriptions = ConcurrentHashMap<PostId, MutableSet<DefaultWebSocketSession>>()

    // 会话到用户的反向映射：WebSocketSession -> UserId
    // 用于连接断开时快速查找该连接属于哪个用户
    private val sessionToUser = ConcurrentHashMap<DefaultWebSocketSession, UserId>()

    /**
     * 添加用户会话
     *
     * 当用户建立 WebSocket 连接时调用
     *
     * @param userId 用户 ID
     * @param session WebSocket 会话
     */
    fun addUserSession(userId: UserId, session: DefaultWebSocketSession) {
        userSessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
        sessionToUser[session] = userId

        logger.info("User session added: userId={}, totalSessions={}", userId.value, userSessions[userId]?.size ?: 0)
    }

    /**
     * 移除用户会话
     *
     * 当连接断开时调用，自动清理所有相关订阅
     *
     * @param session WebSocket 会话
     */
    fun removeUserSession(session: DefaultWebSocketSession) {
        val userId = sessionToUser.remove(session) ?: return

        // 从用户会话集合中移除
        userSessions[userId]?.remove(session)
        if (userSessions[userId]?.isEmpty() == true) {
            userSessions.remove(userId)
        }

        // 清理该会话的所有 Post 订阅
        postSubscriptions.values.forEach { sessions ->
            sessions.remove(session)
        }

        // 清理空的订阅集合
        postSubscriptions.entries.removeIf { it.value.isEmpty() }

        logger.info("User session removed: userId={}, remainingSessions={}", userId.value, userSessions[userId]?.size ?: 0)
    }

    /**
     * 订阅 Post 更新
     *
     * 用户进入 Post 详情页时调用
     *
     * @param userId 用户 ID
     * @param postId Post ID
     * @param session WebSocket 会话
     */
    fun subscribeToPost(userId: UserId, postId: PostId, session: DefaultWebSocketSession) {
        postSubscriptions.computeIfAbsent(postId) { ConcurrentHashMap.newKeySet() }.add(session)

        logger.info(
            "User subscribed to post: userId={}, postId={}, totalSubscribers={}",
            userId.value,
            postId.value,
            postSubscriptions[postId]?.size ?: 0
        )
    }

    /**
     * 取消订阅 Post
     *
     * 用户离开 Post 详情页时调用
     *
     * @param postId Post ID
     * @param session WebSocket 会话
     */
    fun unsubscribeFromPost(postId: PostId, session: DefaultWebSocketSession) {
        postSubscriptions[postId]?.remove(session)

        // 如果没有订阅者了，删除该 Post 的订阅记录
        if (postSubscriptions[postId]?.isEmpty() == true) {
            postSubscriptions.remove(postId)
        }

        logger.info(
            "User unsubscribed from post: postId={}, remainingSubscribers={}",
            postId.value,
            postSubscriptions[postId]?.size ?: 0
        )
    }

    /**
     * 广播消息给所有在线用户
     *
     * 用于新 Post 创建等全局事件
     *
     * @param message JSON 消息
     */
    suspend fun broadcastToAll(message: String) {
        var successCount = 0
        val staleSessions = mutableSetOf<DefaultWebSocketSession>()

        userSessions.values.forEach { sessions ->
            sessions.forEach { session ->
                try {
                    session.send(Frame.Text(message))
                    successCount++
                } catch (e: ClosedSendChannelException) {
                    // 连接已关闭，标记待清理
                    staleSessions.add(session)
                } catch (e: Exception) {
                    logger.error("Failed to send message to session", e)
                    staleSessions.add(session)
                }
            }
        }

        // 批量清理断开的连接
        staleSessions.forEach { removeUserSession(it) }

        logger.info(
            "Broadcasted to all users: success={}, cleaned={}",
            successCount,
            staleSessions.size
        )
    }

    /**
     * 发送消息给特定用户的所有会话
     *
     * 用于私信、@提及等定向通知（未来扩展）
     *
     * @param userId 用户 ID
     * @param message JSON 消息
     */
    suspend fun sendToUser(userId: UserId, message: String) {
        val sessions = userSessions[userId] ?: return

        var successCount = 0
        val staleSessions = mutableSetOf<DefaultWebSocketSession>()

        sessions.forEach { session ->
            try {
                session.send(Frame.Text(message))
                successCount++
            } catch (e: ClosedSendChannelException) {
                // 连接已关闭，标记待清理
                staleSessions.add(session)
            } catch (e: Exception) {
                logger.error("Failed to send message to user: userId={}", userId.value, e)
                staleSessions.add(session)
            }
        }

        // 清理断开的连接
        staleSessions.forEach { removeUserSession(it) }

        logger.debug(
            "Sent message to user: userId={}, success={}, cleaned={}",
            userId.value,
            successCount,
            staleSessions.size
        )
    }

    /**
     * 发送消息给订阅特定 Post 的所有用户
     *
     * 用于 Post 点赞、评论等事件
     *
     * @param postId Post ID
     * @param message JSON 消息
     */
    suspend fun sendToPostSubscribers(postId: PostId, message: String) {
        val sessions = postSubscriptions[postId] ?: return

        var successCount = 0
        val staleSessions = mutableSetOf<DefaultWebSocketSession>()

        sessions.forEach { session ->
            try {
                session.send(Frame.Text(message))
                successCount++
            } catch (e: ClosedSendChannelException) {
                // 连接已关闭，标记待清理
                staleSessions.add(session)
            } catch (e: Exception) {
                logger.error("Failed to send message to post subscriber", e)
                staleSessions.add(session)
            }
        }

        // 清理断开的连接
        staleSessions.forEach { removeUserSession(it) }

        logger.info(
            "Sent message to post subscribers: postId={}, success={}, cleaned={}",
            postId.value,
            successCount,
            staleSessions.size
        )
    }

    /**
     * 获取在线用户数
     */
    fun getOnlineUserCount(): Int = userSessions.size

    /**
     * 检查用户是否在线
     */
    fun isUserOnline(userId: UserId): Boolean = userSessions.containsKey(userId)

    /**
     * 获取一批用户的在线状态
     */
    fun getOnlineStatus(userIds: Collection<UserId>): Map<UserId, Boolean> =
        userIds.associateWith { isUserOnline(it) }

    /**
     * 获取特定 Post 的订阅者数量
     */
    fun getPostSubscriberCount(postId: PostId): Int = postSubscriptions[postId]?.size ?: 0
}
