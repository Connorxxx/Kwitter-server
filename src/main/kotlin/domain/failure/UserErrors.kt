package com.connor.domain.failure

import com.connor.domain.model.UserId
import com.connor.domain.model.Username

sealed interface UserError {
    // Username 相关错误
    data class InvalidUsername(val reason: String) : UserError
    data class UsernameAlreadyExists(val username: String) : UserError

    // Bio 相关错误
    data class InvalidBio(val reason: String) : UserError

    // 用户查询错误
    data class UserNotFound(val userId: UserId) : UserError
    data class UserNotFoundByUsername(val username: Username) : UserError

    // 头像相关错误
    data class InvalidAvatarType(val received: String, val allowed: Set<String>) : UserError
    data class AvatarTooLarge(val size: Long, val maxSize: Long) : UserError
    data class AvatarUploadFailed(val reason: String) : UserError

    // 关注相关错误
    data object CannotFollowSelf : UserError
    data object AlreadyFollowing : UserError
    data object NotFollowing : UserError
    data class FollowTargetNotFound(val userId: UserId) : UserError

    // 拉黑相关错误
    data object CannotBlockSelf : UserError
    data object AlreadyBlocked : UserError
    data object NotBlocked : UserError
    data class BlockTargetNotFound(val userId: UserId) : UserError
    data class UserBlocked(val userId: UserId) : UserError
}
