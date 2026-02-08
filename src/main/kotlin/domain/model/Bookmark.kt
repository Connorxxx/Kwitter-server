package com.connor.domain.model

/**
 * Bookmark ID - 类型安全的标识符
 */
@JvmInline
value class BookmarkId(val value: String)

/**
 * Bookmark - 收藏聚合根
 *
 * 业务规则：
 * - 每个用户只能收藏一个Post一次
 * - 收藏必须关联到存在的Post
 */
data class Bookmark(
    val id: BookmarkId,
    val userId: UserId,
    val postId: PostId,
    val createdAt: Long = System.currentTimeMillis()
)
