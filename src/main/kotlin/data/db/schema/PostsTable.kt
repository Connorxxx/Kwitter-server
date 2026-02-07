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
