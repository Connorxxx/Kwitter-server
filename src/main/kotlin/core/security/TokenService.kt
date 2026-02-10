package com.connor.core.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

class TokenService(
    private val config: TokenConfig // 将配置封装成 data class 注入进来
) {
    /**
     * 生成 JWT Token
     *
     * @param userId 用户ID
     * @param displayName 显示名称
     * @param username 用户名
     * @return JWT Token 字符串
     */
    fun generate(userId: String, displayName: String, username: String): String {
        return JWT.create()
            .withAudience(config.audience)
            .withIssuer(config.domain)
            .withClaim("id", userId) // 用户ID
            .withClaim("displayName", displayName) // 显示名称
            .withClaim("username", username) // 用户名
            .withExpiresAt(Date(System.currentTimeMillis() + config.expiresIn))
            .sign(Algorithm.HMAC256(config.secret))
    }
}

data class TokenConfig(
    val domain: String,
    val audience: String,
    val secret: String,
    val realm: String,
    val expiresIn: Long = 14L * 24 * 60 * 60 * 1000 // 14天过期
)