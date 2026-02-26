package com.connor.core.security

import com.connor.core.http.ApiErrorResponse
import com.connor.domain.model.UserId
import com.connor.domain.repository.UserRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SensitiveRoute")

/**
 * 敏感路由插件
 *
 * 在 JWT 验证之上增加数据库层面的强校验：
 * 1. 用户是否存在（可能已被删除/封禁）
 * 2. 密码是否在 token 签发后被修改（passwordChangedAt > issuedAt）
 *
 * 用法：
 * ```
 * authenticate("auth-jwt") {
 *     sensitive(userRepository) {
 *         post("/change-password") { ... }
 *         delete("/account") { ... }
 *     }
 * }
 * ```
 */
fun Route.sensitive(
    userRepository: UserRepository,
    build: Route.() -> Unit
) {
    val sensitivePlugin = createRouteScopedPlugin("SensitiveAuth") {
        onCall { call ->
            val principal = call.principal<UserPrincipal>() ?: return@onCall

            val user = userRepository.findById(UserId(principal.userId)).getOrNull()

            if (user == null) {
                logger.warn("Sensitive route: user not found, userId={}", principal.userId)
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiErrorResponse(
                        code = "SESSION_REVOKED",
                        message = "用户不存在或已被禁用，请重新登录"
                    )
                )
                return@onCall
            }

            // 检查密码是否在 token 签发后被修改
            if (user.passwordChangedAt > principal.issuedAt) {
                logger.warn(
                    "Sensitive route: password changed after token issued, userId={}, " +
                    "passwordChangedAt={}, tokenIssuedAt={}",
                    principal.userId, user.passwordChangedAt, principal.issuedAt
                )
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiErrorResponse(
                        code = "SESSION_REVOKED",
                        message = "密码已修改，请重新登录"
                    )
                )
                return@onCall
            }
        }
    }

    install(sensitivePlugin)
    build()
}

