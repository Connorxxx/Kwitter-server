package com.connor.core.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

class TokenService(
    private val config: TokenConfig // 将配置封装成 data class 注入进来
) {
    // 1. 生成 Token (发房卡)
    fun generate(userId: String): String {
        return JWT.create()
            .withAudience(config.audience)
            .withIssuer(config.domain)
            .withClaim("id", userId) // 把 userId 藏在 Token 里
            .withExpiresAt(Date(System.currentTimeMillis() + config.expiresIn))
            .sign(Algorithm.HMAC256(config.secret)) // 盖章签字
    }
}

data class TokenConfig(
    val domain: String,
    val audience: String,
    val secret: String,
    val realm: String,
    val expiresIn: Long = 365L * 24 * 60 * 60 * 1000 // 1年过期 (简单的做法)
)