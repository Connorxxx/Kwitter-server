package com.connor.domain.failure

sealed interface AuthError {
    data class UserAlreadyExists(val email: String) : AuthError
    data object InvalidCredentials : AuthError // 模糊报错，防止枚举攻击
    data class InvalidEmail(val value: String) : AuthError // 邮箱格式错误
    data class WeakPassword(val reason: String) : AuthError // 密码强度不足
    data class InvalidDisplayName(val reason: String) : AuthError // 昵称格式错误

    // Refresh Token 相关错误
    data object RefreshTokenExpired : AuthError
    data object RefreshTokenRevoked : AuthError
    data object RefreshTokenNotFound : AuthError
    data object TokenFamilyReused : AuthError       // Reuse Detection: 旧token被重放，整个family已撤销
    data object SessionRevoked : AuthError           // 敏感路由：用户会话已被撤销（密码已修改等）
}
