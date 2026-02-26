package com.connor.domain.model

/**
 * Like ID - 类型安全的标识符
 */
@JvmInline
value class LikeId(val value: Long)

/**
 * Like - 点赞聚合根
 *
 * 业务规则：
 * - 每个用户只能对一个Post点赞一次
 * - 点赞必须关联到存在的Post
 */
data class Like(
    val id: LikeId,
    val userId: UserId,
    val postId: PostId,
    val createdAt: Long = System.currentTimeMillis()
)
