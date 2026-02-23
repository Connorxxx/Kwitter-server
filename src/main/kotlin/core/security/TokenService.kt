package com.connor.core.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.connor.domain.service.TokenHasher
import com.connor.domain.service.TokenIssuer
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Date

class TokenService(
    private val config: TokenConfig
) : TokenIssuer {
    /**
     * 生成 JWT Token（短期：3分钟）
     *
     * 包含 issuedAt 声明，用于敏感路由校验 passwordChangedAt > issuedAt
     * 配合服务端 15 秒 leeway，实际有效窗口 ~3:15
     */
    override fun generate(userId: String, displayName: String, username: String): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withAudience(config.audience)
            .withIssuer(config.domain)
            .withClaim("id", userId)
            .withClaim("displayName", displayName)
            .withClaim("username", username)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + config.expiresIn))
            .sign(Algorithm.HMAC256(config.secret))
    }
}

class RefreshTokenService : TokenHasher {
    private val secureRandom = SecureRandom()

    /**
     * 生成安全的随机 refresh token 字符串
     */
    override fun generateToken(): String {
        val bytes = ByteArray(48)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 对 refresh token 做 SHA-256 哈希（数据库只存哈希值）
     */
    override fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(token.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}

data class TokenConfig(
    val domain: String,
    val audience: String,
    val secret: String,
    val realm: String,
    val expiresIn: Long = 3L * 60 * 1000, // 3分钟过期（配合服务端 15s leeway，实际窗口 ~3:15）
    val refreshTokenExpiresIn: Long = 14L * 24 * 60 * 60 * 1000, // 14天过期
    val refreshTokenGracePeriod: Long = 10_000 // 10秒宽限期（并发刷新）
)
