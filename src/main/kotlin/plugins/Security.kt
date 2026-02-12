package com.connor.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.connor.core.security.TokenConfig
import com.connor.core.security.UserPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*

private lateinit var jwtVerifier: JWTVerifier

fun Application.configureSecurity(tokenConfig: TokenConfig) {
    jwtVerifier = JWT.require(Algorithm.HMAC256(tokenConfig.secret))
        .withAudience(tokenConfig.audience)
        .withIssuer(tokenConfig.domain)
        .build()

    install(Authentication) {
        jwt("auth-jwt") {
            realm = tokenConfig.realm

            verifier { jwtVerifier }
            validate { credential ->
                val userId = credential.payload.getClaim("id").asString()
                val displayName = credential.payload.getClaim("displayName").asString()
                val username = credential.payload.getClaim("username").asString()
                val issuedAt = credential.payload.issuedAt?.time ?: 0L

                if (!userId.isNullOrBlank() && !displayName.isNullOrBlank() && !username.isNullOrBlank()) {
                    UserPrincipal(userId, displayName, username, issuedAt)
                } else {
                    null
                }
            }
            challenge { _, _ ->
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
 * 软鉴权：从请求中尝试解析 JWT，失败时静默返回 null。
 * 用于公开路由附带身份上下文，不会因无效/过期 token 而返回 401。
 */
fun ApplicationCall.tryResolvePrincipal(): UserPrincipal? {
    val authHeader = request.header(HttpHeaders.Authorization) ?: return null
    if (!authHeader.startsWith("Bearer ", ignoreCase = true)) return null
    val token = authHeader.removePrefix("Bearer ").trim()
    if (token.isBlank()) return null

    return try {
        val decoded = jwtVerifier.verify(token)
        val userId = decoded.getClaim("id").asString()
        val displayName = decoded.getClaim("displayName").asString()
        val username = decoded.getClaim("username").asString()
        val issuedAt = decoded.issuedAt?.time ?: 0L

        if (!userId.isNullOrBlank() && !displayName.isNullOrBlank() && !username.isNullOrBlank()) {
            UserPrincipal(userId, displayName, username, issuedAt)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}
