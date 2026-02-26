package com.connor.domain.model

/**
 * Refresh Token 领域模型
 *
 * Token Family 机制：
 * - 同一次登录会话产生的所有轮换 token 共享一个 familyId
 * - 如果有人使用已轮换的旧 token（Reuse Detection），撤销整个 family
 * - 这是 OAuth2 Refresh Token Rotation 的标配安全机制
 */
data class RefreshToken(
    val id: Long,
    val tokenHash: String,
    val userId: UserId,
    val familyId: Long,
    val expiresAt: Long,
    val isRevoked: Boolean = false,
    val revokedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAt
}
