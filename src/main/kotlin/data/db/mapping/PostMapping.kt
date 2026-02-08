package com.connor.data.db.mapping

import com.connor.data.db.schema.MediaTable
import com.connor.data.db.schema.PostsTable
import com.connor.domain.model.*
import org.jetbrains.exposed.sql.ResultRow

/**
 * ResultRow -> Post 领域模型映射
 * 只包含 Post 本身的数据，不包含作者、统计信息等聚合数据
 */
fun ResultRow.toPost(): Post {
    return Post(
        id = PostId(this[PostsTable.id]),
        authorId = UserId(this[PostsTable.authorId]),
        content = PostContent.unsafe(this[PostsTable.content]), // 数据库中的数据已验证
        media = emptyList(), // 媒体附件需要单独查询并填充
        parentId = this[PostsTable.parentId]?.let { PostId(it) },
        createdAt = this[PostsTable.createdAt],
        updatedAt = this[PostsTable.updatedAt]
    )
}

/**
 * ResultRow -> PostStats 映射
 */
fun ResultRow.toPostStats(): PostStats {
    return PostStats(
        postId = PostId(this[PostsTable.id]),
        replyCount = this[PostsTable.replyCount],
        likeCount = this[PostsTable.likeCount],
        bookmarkCount = this[PostsTable.bookmarkCount],
        viewCount = this[PostsTable.viewCount]
    )
}

/**
 * ResultRow -> MediaAttachment 映射
 */
fun ResultRow.toMediaAttachment(): MediaAttachment {
    return MediaAttachment(
        url = MediaUrl.unsafe(this[MediaTable.url]), // 数据库中的数据已验证
        type = MediaType.valueOf(this[MediaTable.type]),
        order = this[MediaTable.order]
    )
}
