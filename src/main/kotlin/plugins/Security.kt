package com.connor.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.connor.core.security.TokenConfig
import com.connor.core.security.UserPrincipal
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

fun Application.configureSecurity(tokenConfig: TokenConfig) {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = tokenConfig.realm

            verifier {
                JWT.require(Algorithm.HMAC256(tokenConfig.secret))
                    .withAudience(tokenConfig.audience)
                    .withIssuer(tokenConfig.domain)
                    .build()
            }
            validate { credential ->
                // 从 JWT 中提取 userId，返回自定义的 UserPrincipal
                val userId = credential.payload.getClaim("id").asString()
                userId?.let { UserPrincipal(userId) }
            }
        }
    }
}
