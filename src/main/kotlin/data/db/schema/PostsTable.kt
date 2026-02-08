package com.connor.data.db.schema

import org.jetbrains.exposed.sql.Table

/**
 * Posts 表 - 存储所有 Post（包括顶层 Post 和回复）
 *
 * 设计要点：
 * - parentId 为 null = 顶层 Post
 * - parentId 非 null = 回复（reply）
 * - 媒体附件单独存储在 MediaTable 中（1:N 关系）
 */
object PostsTable : Table("posts") {
    // Post ID (UUID)
    val id = varchar("id", 36)

    // 作者 ID（外键指向 users 表）
    val authorId = varchar("author_id", 36)
        .references(UsersTable.id)

    // Post 内容（280 字符限制）
    val content = varchar("content", 280)

    // 父 Post ID（null = 顶层 Post，非 null = 回复）
    val parentId = varchar("parent_id", 36)
        .references(PostsTable.id)
        .nullable()

    // 时间戳
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    // 统计信息（冗余字段，优化查询性能）
    val replyCount = integer("reply_count").default(0)
    val likeCount = integer("like_count").default(0)
    val bookmarkCount = integer("bookmark_count").default(0)
    val viewCount = integer("view_count").default(0)

    override val primaryKey = PrimaryKey(id)
}

/**
 * 媒体附件表 - 存储 Post 的图片/视频
 *
 * 设计要点：
 * - 一个 Post 最多 4 个媒体
 * - order 字段用于排序（0-3）
 */
object MediaTable : Table("media") {
    // 媒体 ID
    val id = varchar("id", 36)

    // 所属 Post ID（外键）
    val postId = varchar("post_id", 36)
        .references(PostsTable.id)

    // 媒体 URL
    val url = varchar("url", 500)

    // 媒体类型（IMAGE, VIDEO）
    val type = varchar("type", 10)

    // 排序顺序（0-3）
    val order = integer("order")

    override val primaryKey = PrimaryKey(id)
}

/**
 * 点赞表 - 存储用户对 Post 的点赞记录
 *
 * 设计要点：
 * - 一个用户只能对一个 Post 点赞一次（复合唯一约束）
 * - 通过 UK_USER_POST_LIKE 索引避免重复
 */
object LikesTable : Table("likes") {
    // 点赞 ID
    val id = varchar("id", 36)

    // 用户 ID（外键）
    val userId = varchar("user_id", 36)
        .references(UsersTable.id)

    // Post ID（外键）
    val postId = varchar("post_id", 36)
        .references(PostsTable.id)

    // 创建时间
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        // 复合唯一约束：同一用户不能对同一Post重复点赞
        uniqueIndex("uk_user_post_like", userId, postId)
    }
}

/**
 * 收藏表 - 存储用户对 Post 的收藏记录
 *
 * 设计要点：
 * - 一个用户只能收藏一个 Post 一次（复合唯一约束）
 * - 通过 UK_USER_POST_BOOKMARK 索引避免重复
 */
object BookmarksTable : Table("bookmarks") {
    // 收藏 ID
    val id = varchar("id", 36)

    // 用户 ID（外键）
    val userId = varchar("user_id", 36)
        .references(UsersTable.id)

    // Post ID（外键）
    val postId = varchar("post_id", 36)
        .references(PostsTable.id)

    // 创建时间
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        // 复合唯一约束：同一用户不能重复收藏同一Post
        uniqueIndex("uk_user_post_bookmark", userId, postId)
    }
}
