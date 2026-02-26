package com.connor.data.db.schema

import org.jetbrains.exposed.v1.core.Table

/**
 * Follows 表 - 关注关系
 *
 * 设计约束：
 * - (follower_id, following_id) 组合唯一
 * - follower_id 和 following_id 都是外键引用 users 表
 * - 索引优化：follower_id（查询我关注的人）、following_id（查询关注我的人）
 */
object FollowsTable : Table("follows") {
    // 关注者 ID（外键引用 users）
    val followerId = long("follower_id").references(UsersTable.id)

    // 被关注者 ID（外键引用 users）
    val followingId = long("following_id").references(UsersTable.id)

    // 关注时间
    val createdAt = long("created_at")

    // 组合主键：(follower_id, following_id) 确保唯一性
    override val primaryKey = PrimaryKey(followerId, followingId)

    // 索引优化：
    // 1. 查询 "我关注的人" 时使用 follower_id
    // 2. 查询 "关注我的人" 时使用 following_id
    init {
        index("idx_follows_follower", false, followerId)
        index("idx_follows_following", false, followingId)
    }
}
