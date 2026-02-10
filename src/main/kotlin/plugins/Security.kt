package com.connor.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.connor.core.security.TokenConfig
import com.connor.core.security.UserPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

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
                // 确保 userId 不为空且有效
                if (!userId.isNullOrBlank()) {
                    UserPrincipal(userId)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                // 自定义认证失败响应
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf(
                        "code" to "INVALID_TOKEN",
                        "message" to "Token is invalid or has expired"
                    )
                )
            }
        }
    }
}

/**
 * 可选认证路由块
 * 允许认证用户和未认证用户访问
 * 认证用户可以通过 call.principal<UserPrincipal>() 获取用户信息
 * 未认证用户 call.principal<UserPrincipal>() 返回 null
 */
inline fun Route.authenticateOptional(
    name: String,
    crossinline build: Route.() -> Unit
) {
    authenticate(name, optional = true) {
        build()
    }
}
