package com.connor.domain.failure

sealed interface AuthError {
    data class UserAlreadyExists(val email: String) : AuthError
    data object InvalidCredentials : AuthError // 模糊报错，防止枚举攻击
    data class InvalidFormat(val reason: String) : AuthError // 邮箱格式错误等
}