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

    // 关注相关错误
    data object CannotFollowSelf : UserError
    data object AlreadyFollowing : UserError
    data object NotFollowing : UserError
    data class FollowTargetNotFound(val userId: UserId) : UserError
}
