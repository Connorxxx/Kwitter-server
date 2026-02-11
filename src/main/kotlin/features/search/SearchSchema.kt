package com.connor.features.search

import com.connor.features.post.PostDetailResponse
import com.connor.features.user.UserDto
import com.connor.features.user.UserStatsDto
import kotlinx.serialization.Serializable

// ========== Response DTOs ==========

/**
 * 搜索 Posts 响应
 */
@Serializable
data class SearchPostsResponse(
    val posts: List<PostDetailResponse>,
    val hasMore: Boolean,
    val query: String,
    val sort: String // "relevance" or "recent"
)

/**
 * 搜索 Replies 响应
 */
@Serializable
data class SearchRepliesResponse(
    val replies: List<PostDetailResponse>,
    val hasMore: Boolean,
    val query: String
)

/**
 * 搜索用户响应
 */
@Serializable
data class SearchUsersResponse(
    val users: List<UserSearchResultDto>,
    val hasMore: Boolean,
    val query: String
)

/**
 * 用户搜索结果 DTO（包含统计信息和关注状态）
 */
@Serializable
data class UserSearchResultDto(
    val user: UserDto,
    val stats: UserStatsDto,
    val isFollowedByCurrentUser: Boolean? = null // null = 当前用户未认证
)
