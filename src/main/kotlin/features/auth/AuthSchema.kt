package com.connor.features.auth

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class RefreshRequest(
    val refreshToken: String
)

@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String? = null,
    val createdAt: Long,
    val token: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long? = null // JWT 过期时间（毫秒），客户端可用于定时刷新
)

@Serializable
data class TokenResponse(
    val token: String,
    val refreshToken: String,
    val expiresIn: Long
)

// 标准化错误响应结构，方便 Android 端统一解析
@Serializable
data class ErrorResponse(
    val code: String,
    val message: String
)
