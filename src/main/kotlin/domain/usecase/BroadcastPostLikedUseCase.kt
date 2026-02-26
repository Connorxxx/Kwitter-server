package com.connor.domain.usecase

import com.connor.domain.model.NotificationEvent
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.repository.NotificationRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * 广播 Post 点赞事件 Use Case
 *
 * 职责：
 * - 当 Post 被点赞时触发实时推送
 * - 推送给订阅该 Post 的所有用户
 * - 使用结构化并发，由调用方控制生命周期
 *
 * 业务规则：
 * 1. 推送给订阅该 Post 的用户（正在查看详情页的用户）
 * 2. 包含最新的点赞数
 * 3. 推送失败不影响点赞操作成功（调用方处理）
 */
class BroadcastPostLikedUseCase(
    private val notificationRepository: NotificationRepository
) {
    private val logger = LoggerFactory.getLogger(BroadcastPostLikedUseCase::class.java)

    /**
     * 执行广播（suspend 函数，不启动新协程）
     *
     * @param postId Post ID
     * @param likedByUserId 点赞用户 ID
     * @param likedByDisplayName 点赞用户显示名称
     * @param likedByUsername 点赞用户名
     * @param newLikeCount 最新点赞数
     */
    suspend fun execute(
        postId: PostId,
        likedByUserId: UserId,
        likedByDisplayName: String,
        likedByUsername: String,
        newLikeCount: Int
    ) {
        try {
            val event = NotificationEvent.PostLiked(
                postId = postId.value,
                likedByUserId = likedByUserId.value,
                likedByDisplayName = likedByDisplayName,
                likedByUsername = likedByUsername,
                newLikeCount = newLikeCount,
                timestamp = System.currentTimeMillis()
            )

            notificationRepository.notifyPostLiked(event)

            logger.info(
                "Broadcasted post liked event: postId={}, likedBy={}, newLikeCount={}",
                postId.value,
                likedByUserId.value,
                newLikeCount
            )
        } catch (e: CancellationException) {
            // 重新抛出取消异常，保持协程取消语义
            throw e
        } catch (e: Exception) {
            // 记录错误但不传播异常（推送失败不应影响主流程）
            logger.error(
                "Failed to broadcast post liked event: postId={}, likedBy={}",
                postId.value,
                likedByUserId.value,
                e
            )
        }
    }
}
