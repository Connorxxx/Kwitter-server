package com.connor.features.post

import kotlinx.serialization.Serializable

// ========== Request DTOs ==========

/**
 * 创建 Post 请求（顶层 Post 或回复）
 */
@Serializable
data class CreatePostRequest(
    val content: String,
    val mediaUrls: List<MediaDto> = emptyList(), // 最多 4 个
    val parentId: Long? = null // null = 顶层 Post，非 null = 回复
)

/**
 * 媒体 DTO
 */
@Serializable
data class MediaDto(
    val url: String,
    val type: String // "IMAGE" 或 "VIDEO"
)

// ========== Response DTOs ==========

/**
 * Post 详情响应（包含作者信息、统计数据）
 */
@Serializable
data class PostDetailResponse(
    val id: Long,
    val content: String,
    val media: List<MediaDto>,
    val parentId: Long?,
    val isTopLevelPost: Boolean, // 明确标记是否为顶层 Post
    val createdAt: Long,
    val updatedAt: Long,
    val author: AuthorDto,
    val stats: StatsDto,
    val parentPost: PostSummaryResponse? = null, // 如果是回复，包含父 Post 摘要
    // 当前用户的交互状态（认证用户可见）
    val isLikedByCurrentUser: Boolean? = null,
    val isBookmarkedByCurrentUser: Boolean? = null
)

/**
 * Post 摘要响应（用于嵌套显示父 Post）
 */
@Serializable
data class PostSummaryResponse(
    val id: Long,
    val content: String,
    val author: AuthorDto,
    val createdAt: Long
)

/**
 * 作者信息 DTO
 */
@Serializable
data class AuthorDto(
    val id: Long,
    val displayName: String,
    val avatarUrl: String? = null
)

/**
 * 统计信息 DTO
 */
@Serializable
data class StatsDto(
    val replyCount: Int,
    val likeCount: Int,
    val bookmarkCount: Int,
    val viewCount: Int
)

/**
 * 分页响应包装
 */
@Serializable
data class PostListResponse(
    val posts: List<PostDetailResponse>,
    val hasMore: Boolean,
    val total: Int? = null, // 可选的总数（某些场景不需要）
    val nextCursor: Long? = null // 最后一条记录的 snowflake ID，用于 cursor 分页
)
