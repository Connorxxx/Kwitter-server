package com.connor.domain.repository

import com.connor.domain.model.NotificationEvent
import com.connor.domain.model.UserId

/**
 * 通知推送 Repository 接口
 *
 * 职责：
 * - 定义通知推送的契约
 * - 隔离 Domain 层与 Infrastructure 层（WebSocket 实现）
 * - 支持多种推送策略（广播、定向推送等）
 *
 * 实现：
 * - InMemoryNotificationRepository（单机版，内存管理连接）
 * - RedisNotificationRepository（分布式版，未来扩展）
 */
interface NotificationRepository {
    /**
     * 广播新 Post 创建事件
     *
     * 推送对象：所有在线用户
     * 业务规则：仅广播顶层 Post（非回复）
     * 失败处理：记录日志但不阻塞主流程
     *
     * @param event 新 Post 创建事件
     */
    suspend fun broadcastNewPost(event: NotificationEvent.NewPostCreated)

    /**
     * 通知 Post 被点赞
     *
     * 推送对象：订阅该 Post 的所有用户
     * 业务规则：实时更新点赞数
     * 失败处理：记录日志但不阻塞主流程
     *
     * @param event Post 点赞事件
     */
    suspend fun notifyPostLiked(event: NotificationEvent.PostLiked)

    /**
     * 通知 Post 被评论/回复
     *
     * 推送对象：Post 作者和订阅该 Post 的用户
     * 业务规则：Post 作者始终收到通知，即使未订阅
     * 失败处理：记录日志但不阻塞主流程
     *
     * @param event Post 评论事件
     */
    suspend fun notifyPostCommented(event: NotificationEvent.PostCommented)

    /**
     * 通知用户收到新私信
     *
     * 推送对象：消息接收者
     * 失败处理：记录日志但不阻塞主流程
     *
     * @param recipientId 消息接收者 ID
     * @param event 新私信事件
     */
    suspend fun notifyNewMessage(recipientId: UserId, event: NotificationEvent.NewMessageReceived)

    /**
     * 通知消息发送者对方已读
     *
     * 推送对象：消息发送者
     * 失败处理：记录日志但不阻塞主流程
     *
     * @param recipientId 通知接收者 ID（消息发送者）
     * @param event 已读事件
     */
    suspend fun notifyMessagesRead(recipientId: UserId, event: NotificationEvent.MessagesRead)
}
