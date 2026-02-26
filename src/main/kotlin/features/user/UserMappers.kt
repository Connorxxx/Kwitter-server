package com.connor.features.user

import com.connor.core.http.ApiErrorResponse
import com.connor.domain.failure.UserError
import com.connor.domain.model.User
import com.connor.domain.model.UserProfile
import com.connor.domain.model.UserStats
import com.connor.domain.usecase.GetUserFollowersUseCase
import com.connor.domain.usecase.GetUserFollowingUseCase
import com.connor.domain.usecase.GetUserProfileUseCase
import io.ktor.http.*

// ========== Domain -> Response DTO ==========

/**
 * User -> UserDto
 */
fun User.toDto(): UserDto {
    return UserDto(
        id = id.value.toString(),
        username = username.value,
        displayName = displayName.value,
        bio = bio.value,
        avatarUrl = avatarUrl,
        createdAt = createdAt
    )
}

/**
 * UserStats -> UserStatsDto
 */
fun UserStats.toDto(): UserStatsDto {
    return UserStatsDto(
        followingCount = followingCount,
        followersCount = followersCount,
        postsCount = postsCount
    )
}

/**
 * UserProfile -> UserProfileResponse
 */
fun UserProfile.toResponse(
    isFollowedByCurrentUser: Boolean? = null,
    isBlockedByCurrentUser: Boolean? = null
): UserProfileResponse {
    return UserProfileResponse(
        user = user.toDto(),
        stats = stats.toDto(),
        isFollowedByCurrentUser = isFollowedByCurrentUser,
        isBlockedByCurrentUser = isBlockedByCurrentUser
    )
}

/**
 * GetUserProfileUseCase.ProfileView -> UserProfileResponse
 */
fun GetUserProfileUseCase.ProfileView.toResponse(): UserProfileResponse {
    return profile.toResponse(isFollowedByCurrentUser, isBlockedByCurrentUser)
}

/**
 * GetUserFollowingUseCase.FollowingItem -> UserListItemDto
 */
fun GetUserFollowingUseCase.FollowingItem.toDto(): UserListItemDto {
    return UserListItemDto(
        user = user.toDto(),
        isFollowedByCurrentUser = isFollowedByCurrentUser
    )
}

/**
 * GetUserFollowersUseCase.FollowerItem -> UserListItemDto
 */
fun GetUserFollowersUseCase.FollowerItem.toDto(): UserListItemDto {
    return UserListItemDto(
        user = user.toDto(),
        isFollowedByCurrentUser = isFollowedByCurrentUser
    )
}

// ========== Error -> HTTP ==========

/**
 * 将用户业务错误映射为 HTTP 状态码和错误响应
 */
fun UserError.toHttpError(): Pair<HttpStatusCode, ApiErrorResponse> = when (this) {
    is UserError.InvalidUsername ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "INVALID_USERNAME",
            message = reason
        )

    is UserError.UsernameAlreadyExists ->
        HttpStatusCode.Conflict to ApiErrorResponse(
            code = "USERNAME_ALREADY_EXISTS",
            message = "用户名 $username 已被占用"
        )

    is UserError.InvalidBio ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "INVALID_BIO",
            message = reason
        )

    is UserError.UserNotFound ->
        HttpStatusCode.NotFound to ApiErrorResponse(
            code = "USER_NOT_FOUND",
            message = "用户不存在: ${userId.value}"
        )

    is UserError.UserNotFoundByUsername ->
        HttpStatusCode.NotFound to ApiErrorResponse(
            code = "USER_NOT_FOUND",
            message = "用户不存在: ${username.value}"
        )

    is UserError.CannotFollowSelf ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "CANNOT_FOLLOW_SELF",
            message = "不能关注自己"
        )

    is UserError.AlreadyFollowing ->
        HttpStatusCode.Conflict to ApiErrorResponse(
            code = "ALREADY_FOLLOWING",
            message = "已经关注该用户"
        )

    is UserError.NotFollowing ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "NOT_FOLLOWING",
            message = "未关注该用户"
        )

    is UserError.FollowTargetNotFound ->
        HttpStatusCode.NotFound to ApiErrorResponse(
            code = "FOLLOW_TARGET_NOT_FOUND",
            message = "目标用户不存在: ${userId.value}"
        )

    is UserError.InvalidAvatarType ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "INVALID_AVATAR_TYPE",
            message = "不支持的头像格式: $received，允许: ${allowed.joinToString(", ")}"
        )

    is UserError.AvatarTooLarge ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "AVATAR_TOO_LARGE",
            message = "头像文件过大: ${size / 1024}KB，最大: ${maxSize / 1024}KB"
        )

    is UserError.AvatarUploadFailed ->
        HttpStatusCode.InternalServerError to ApiErrorResponse(
            code = "AVATAR_UPLOAD_FAILED",
            message = "头像上传失败: $reason"
        )

    is UserError.CannotBlockSelf ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "CANNOT_BLOCK_SELF",
            message = "不能拉黑自己"
        )

    is UserError.AlreadyBlocked ->
        HttpStatusCode.Conflict to ApiErrorResponse(
            code = "ALREADY_BLOCKED",
            message = "已经拉黑该用户"
        )

    is UserError.NotBlocked ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "NOT_BLOCKED",
            message = "未拉黑该用户"
        )

    is UserError.BlockTargetNotFound ->
        HttpStatusCode.NotFound to ApiErrorResponse(
            code = "BLOCK_TARGET_NOT_FOUND",
            message = "目标用户不存在: ${userId.value}"
        )

    is UserError.UserBlocked ->
        HttpStatusCode.Forbidden to ApiErrorResponse(
            code = "USER_BLOCKED",
            message = "用户已被拉黑: ${userId.value}"
        )
}
