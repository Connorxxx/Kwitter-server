package com.connor.domain.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.PostError

/**
 * Post ID - 类型安全的标识符
 */
@JvmInline
value class PostId(val value: String)

/**
 * 媒体类型
 */
enum class MediaType {
    IMAGE,
    VIDEO
}

/**
 * 媒体 URL - 带验证的 Value Object
 */
@JvmInline
value class MediaUrl private constructor(val value: String) {
    companion object {
        operator fun invoke(value: String): Either<PostError, MediaUrl> {
            return if (value.isNotBlank() && value.length <= 500) {
                MediaUrl(value).right()
            } else {
                PostError.InvalidMediaUrl(value).left()
            }
        }

        fun unsafe(value: String): MediaUrl = MediaUrl(value)
    }
}

/**
 * 媒体附件 - Value Object
 */
data class MediaAttachment(
    val url: MediaUrl,
    val type: MediaType,
    val order: Int // 0-3，用于排序显示
)

/**
 * Post 内容 - 带验证的 Value Object
 */
@JvmInline
value class PostContent private constructor(val value: String) {
    companion object {
        private const val MAX_LENGTH = 280 // Twitter style

        operator fun invoke(value: String): Either<PostError, PostContent> {
            return when {
                value.isBlank() -> PostError.EmptyContent.left()
                value.length > MAX_LENGTH -> PostError.ContentTooLong(value.length, MAX_LENGTH).left()
                else -> PostContent(value).right()
            }
        }

        fun unsafe(value: String): PostContent = PostContent(value)
    }
}

/**
 * Post 实体 - 核心领域模型
 *
 * 设计约束：
 * - 每个 Post 属于一个作者 (authorId)
 * - 可以是顶层 Post (parentId = null) 或回复 (parentId != null)
 * - 最多 4 个媒体附件
 */
data class Post(
    val id: PostId,
    val authorId: UserId,
    val content: PostContent,
    val media: List<MediaAttachment> = emptyList(), // 0-4 个媒体
    val parentId: PostId? = null, // null = 顶层 Post，非 null = 回复
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    init {
        require(media.size <= 4) { "Post 最多只能有 4 个媒体附件" }
        // 验证 media order 是否正确 (0, 1, 2, 3)
        require(media.mapIndexed { index, m -> m.order == index }.all { it }) {
            "媒体附件的 order 必须从 0 开始连续递增"
        }
    }
}

/**
 * Post 统计信息 - 聚合根的只读投影
 * 用于展示列表时的性能优化（避免 N+1 查询）
 */
data class PostStats(
    val postId: PostId,
    val replyCount: Int = 0,
    val likeCount: Int = 0,
    val viewCount: Int = 0
)

/**
 * Post 详情视图 - 聚合 Post + Stats + Author 信息
 * 用于 API 返回，减少客户端多次请求
 */
data class PostDetail(
    val post: Post,
    val author: User, // 嵌入作者信息，避免客户端额外请求
    val stats: PostStats,
    val parentPost: Post? = null // 如果是回复，包含父 Post（可选）
)
