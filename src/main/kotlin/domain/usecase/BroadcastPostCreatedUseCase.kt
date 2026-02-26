package com.connor.domain.usecase

import com.connor.domain.model.NotificationEvent
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.repository.NotificationRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * 广播新 Post 创建事件 Use Case
 *
 * 职责：
 * - 当新的顶层 Post 创建时触发实时推送
 * - 推送给所有在线用户
 * - 使用结构化并发，由调用方控制生命周期
 *
 * 业务规则：
 * 1. 仅广播顶层 Post（非回复）
 * 2. 推送失败不影响 Post 创建成功（调用方处理）
 * 3. suspend 函数，生命周期由调用方管理
 */
class BroadcastPostCreatedUseCase(
    private val notificationRepository: NotificationRepository
) {
    private val logger = LoggerFactory.getLogger(BroadcastPostCreatedUseCase::class.java)

    /**
     * 执行广播（suspend 函数，不启动新协程）
     *
     * @param postId Post ID
     * @param authorId 作者 ID
     * @param authorDisplayName 作者显示名称
     * @param authorUsername 作者用户名
     * @param content Post 内容
     * @param createdAt 创建时间戳
     */
    suspend fun execute(
        postId: PostId,
        authorId: UserId,
        authorDisplayName: String,
        authorUsername: String,
        content: String,
        createdAt: Long
    ) {
        try {
            val event = NotificationEvent.NewPostCreated(
                postId = postId.value,
                authorId = authorId.value,
                authorDisplayName = authorDisplayName,
                authorUsername = authorUsername,
                content = content,
                createdAt = createdAt
            )

            notificationRepository.broadcastNewPost(event)

            logger.info(
                "Broadcasted new post created event: postId={}, authorId={}",
                postId.value,
                authorId.value
            )
        } catch (e: CancellationException) {
            // 重新抛出取消异常，保持协程取消语义
            throw e
        } catch (e: Exception) {
            // 记录错误但不传播异常（推送失败不应影响主流程）
            logger.error(
                "Failed to broadcast new post created event: postId={}, authorId={}",
                postId.value,
                authorId.value,
                e
            )
        }
    }
}
