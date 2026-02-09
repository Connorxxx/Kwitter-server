package com.connor.domain.model

/**
 * Follow - 关注关系
 *
 * 设计约束：
 * - followerId 关注 followingId
 * - 数据库层面确保唯一性（composite unique index on (follower_id, following_id)）
 * - 不允许自己关注自己（业务规则在 UseCase 层验证）
 */
data class Follow(
    val followerId: UserId,
    val followingId: UserId,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(followerId != followingId) { "用户不能关注自己" }
    }
}

/**
 * UserProfile - 用户资料聚合视图
 *
 * 用于 API 返回，包含用户基本信息和统计数据
 * 避免客户端多次请求
 */
data class UserProfile(
    val user: User,
    val stats: UserStats
)

/**
 * UserStats - 用户统计信息
 *
 * 设计原理：
 * - 聚合根的只读投影
 * - 用于展示时的性能优化（避免实时计算）
 */
data class UserStats(
    val userId: UserId,
    val followingCount: Int = 0,
    val followersCount: Int = 0,
    val postsCount: Int = 0
)
