package com.connor.domain.failure

sealed interface AuthError {
    data class UserAlreadyExists(val email: String) : AuthError
    data object InvalidCredentials : AuthError // 模糊报错，防止枚举攻击
    data class InvalidEmail(val value: String) : AuthError // 邮箱格式错误
    data class WeakPassword(val reason: String) : AuthError // 密码强度不足
    data class InvalidDisplayName(val reason: String) : AuthError // 昵称格式错误
}