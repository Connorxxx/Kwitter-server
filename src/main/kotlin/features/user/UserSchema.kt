package com.connor.features.user

import kotlinx.serialization.Serializable

// ========== Request DTOs ==========

/**
 * 更新用户资料请求
 */
@Serializable
data class UpdateProfileRequest(
    val username: String? = null,
    val displayName: String? = null,
    val bio: String? = null,
    val avatarUrl: String? = null
)

/**
 * 头像上传响应
 */
@Serializable
data class AvatarUploadResponse(val avatarUrl: String)

// ========== Response DTOs ==========

/**
 * 用户基本信息 DTO
 */
@Serializable
data class UserDto(
    val id: Long,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String?,
    val createdAt: Long
)

/**
 * 用户统计信息 DTO
 */
@Serializable
data class UserStatsDto(
    val followingCount: Int,
    val followersCount: Int,
    val postsCount: Int
)

/**
 * 用户资料响应（包含统计信息）
 */
@Serializable
data class UserProfileResponse(
    val user: UserDto,
    val stats: UserStatsDto,
    val isFollowedByCurrentUser: Boolean? = null, // null = 当前用户未认证或查看自己
    val isBlockedByCurrentUser: Boolean? = null // null = 当前用户未认证或查看自己
)

/**
 * 用户列表项（用于 Following/Followers 列表）
 */
@Serializable
data class UserListItemDto(
    val user: UserDto,
    val isFollowedByCurrentUser: Boolean? = null
)

/**
 * 用户列表响应
 */
@Serializable
data class UserListResponse(
    val users: List<UserListItemDto>,
    val hasMore: Boolean = false
)
