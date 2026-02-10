package com.connor.core.security

/**
 * JWT Token 中的用户信息
 *
 * 包含用户ID、显示名称和用户名，避免在请求处理时额外查询数据库
 */
data class UserPrincipal(
    val userId: String,
    val displayName: String,
    val username: String
)