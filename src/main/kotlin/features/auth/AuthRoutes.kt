package com.connor.features.auth

import com.connor.domain.usecase.LoginUseCase
import com.connor.domain.usecase.RefreshTokenUseCase
import com.connor.domain.usecase.RegisterUseCase
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AuthRoutes")

fun Route.authRoutes(
    registerUseCase: RegisterUseCase,
    loginUseCase: LoginUseCase,
    refreshTokenUseCase: RefreshTokenUseCase
) {
    route("/v1/auth") {

        post("/register") {
            val startTime = System.currentTimeMillis()
            val clientIp = call.request.local.remoteAddress
            val userAgent = call.request.headers["User-Agent"] ?: "Unknown"

            val request = call.receive<RegisterRequest>()
            logger.info(
                "收到注册请求: email=${request.email}, displayName=${request.displayName}, " +
                "clientIp=$clientIp, userAgent=${userAgent.take(100)}"
            )

            val result = registerUseCase(request.toCommand())

            result.fold(
                ifLeft = { error ->
                    val (status, body) = error.toHttpError()
                    val duration = System.currentTimeMillis() - startTime

                    logger.warn(
                        "注册失败: email=${request.email}, error=${error.javaClass.simpleName}, " +
                        "errorCode=${body.code}, statusCode=${status.value}, " +
                        "duration=${duration}ms, clientIp=$clientIp"
                    )

                    call.respond(status, body)
                },
                ifRight = { user ->
                    // 注册成功：创建初始 token pair（JWT + refresh token）
                    val tokenPair = refreshTokenUseCase.createInitialTokenPair(
                        userId = user.id,
                        displayName = user.displayName.value,
                        username = user.username.value
                    )
                    val duration = System.currentTimeMillis() - startTime

                    logger.info(
                        "注册成功: userId=${user.id.value}, email=${user.email.value}, " +
                        "duration=${duration}ms, clientIp=$clientIp"
                    )

                    call.respond(HttpStatusCode.Created, user.toResponse(tokenPair))
                }
            )
        }

        post("/login") {
            val startTime = System.currentTimeMillis()
            val clientIp = call.request.local.remoteAddress
            val userAgent = call.request.headers["User-Agent"] ?: "Unknown"

            val request = call.receive<LoginRequest>()
            logger.info(
                "收到登录请求: email=${request.email}, " +
                "clientIp=$clientIp, userAgent=${userAgent.take(100)}"
            )

            val result = loginUseCase(request.toCommand())

            result.fold(
                ifLeft = { error ->
                    val (status, body) = error.toHttpError()
                    val duration = System.currentTimeMillis() - startTime

                    logger.warn(
                        "登录失败: email=${request.email}, error=${error.javaClass.simpleName}, " +
                        "errorCode=${body.code}, statusCode=${status.value}, " +
                        "duration=${duration}ms, clientIp=$clientIp"
                    )

                    call.respond(status, body)
                },
                ifRight = { user ->
                    // 登录成功：创建初始 token pair（JWT + refresh token）
                    val tokenPair = refreshTokenUseCase.createInitialTokenPair(
                        userId = user.id,
                        displayName = user.displayName.value,
                        username = user.username.value
                    )
                    val duration = System.currentTimeMillis() - startTime

                    logger.info(
                        "登录成功: userId=${user.id.value}, email=${user.email.value}, " +
                        "duration=${duration}ms, clientIp=$clientIp"
                    )

                    call.respond(HttpStatusCode.OK, user.toResponse(tokenPair))
                }
            )
        }

        /**
         * Token 刷新端点（公开接口，不需要 JWT 认证）
         *
         * 流程：JWT 过期 → 401 → 客户端用 refresh token 请求此端点 → 获取新 JWT + 新 refresh token → 重试原请求
         */
        post("/refresh") {
            val startTime = System.currentTimeMillis()
            val clientIp = call.request.local.remoteAddress

            val request = call.receive<RefreshRequest>()
            logger.debug("收到 token 刷新请求: clientIp=$clientIp")

            val result = refreshTokenUseCase(request.refreshToken)

            result.fold(
                ifLeft = { error ->
                    val (status, body) = error.toHttpError()
                    val duration = System.currentTimeMillis() - startTime

                    logger.warn(
                        "Token 刷新失败: error=${error.javaClass.simpleName}, " +
                        "errorCode=${body.code}, duration=${duration}ms, clientIp=$clientIp"
                    )

                    call.respond(status, body)
                },
                ifRight = { tokenPair ->
                    val duration = System.currentTimeMillis() - startTime
                    logger.debug("Token 刷新成功: duration=${duration}ms, clientIp=$clientIp")

                    call.respond(HttpStatusCode.OK, tokenPair.toResponse())
                }
            )
        }
    }
}
