package com.connor.core.security

/**
 * JWT Token 中的用户信息
 *
 * 包含用户ID、显示名称、用户名和token签发时间
 * issuedAt 用于敏感路由校验：如果 passwordChangedAt > issuedAt，则token无效
 */
data class UserPrincipal(
    val userId: String,
    val displayName: String,
    val username: String,
    val issuedAt: Long = 0
)
