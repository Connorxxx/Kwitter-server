package com.connor.domain.usecase

import com.connor.domain.model.NotificationEvent
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.repository.NotificationRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * 广播 Post 取消点赞事件 Use Case
 *
 * 职责：
 * - 当 Post 被取消点赞时触发实时推送
 * - 推送给订阅该 Post 的所有用户
 * - 使用结构化并发，由调用方控制生命周期
 */
class BroadcastPostUnlikedUseCase(
    private val notificationRepository: NotificationRepository
) {
    private val logger = LoggerFactory.getLogger(BroadcastPostUnlikedUseCase::class.java)

    suspend fun execute(
        postId: PostId,
        unlikedByUserId: UserId,
        newLikeCount: Int
    ) {
        try {
            val event = NotificationEvent.PostUnliked(
                postId = postId.value,
                unlikedByUserId = unlikedByUserId.value,
                newLikeCount = newLikeCount,
                isLiked = false,
                timestamp = System.currentTimeMillis()
            )

            notificationRepository.notifyPostUnliked(event)

            logger.info(
                "Broadcasted post unliked event: postId={}, unlikedBy={}, newLikeCount={}",
                postId.value,
                unlikedByUserId.value,
                newLikeCount
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to broadcast post unliked event: postId={}, unlikedBy={}",
                postId.value,
                unlikedByUserId.value,
                e
            )
        }
    }
}
