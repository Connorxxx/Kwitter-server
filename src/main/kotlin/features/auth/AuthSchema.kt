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
data class UserResponse(
    val id: String,
    val email: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String? = null,
    val createdAt: Long,
    val token: String? = null // 注册成功后通常直接返回 Token，省去一次登录
)

// 标准化错误响应结构，方便 Android 端统一解析
@Serializable
data class ErrorResponse(
    val code: String,
    val message: String
)