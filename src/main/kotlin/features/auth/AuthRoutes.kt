package com.connor.features.auth

import com.connor.core.security.TokenService
import com.connor.domain.usecase.LoginUseCase
import com.connor.domain.usecase.RegisterUseCase
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AuthRoutes")

fun Route.authRoutes(
    registerUseCase: RegisterUseCase,
    loginUseCase: LoginUseCase,
    tokenService: TokenService
) {
    route("/v1/auth") {

        post("/register") {
            val startTime = System.currentTimeMillis()

            // 获取客户端信息（用于日志）
            val clientIp = call.request.local.remoteAddress
            val userAgent = call.request.headers["User-Agent"] ?: "Unknown"

            try {
                // 1. 接收并反序列化 JSON
                val request = call.receive<RegisterRequest>()
                logger.info(
                    "收到注册请求: email=${request.email}, displayName=${request.displayName}, " +
                    "clientIp=$clientIp, userAgent=${userAgent.take(100)}"
                )

                // 2. 调用业务逻辑
                val result = registerUseCase(request.toCommand())

                // 3. 处理结果
                result.fold(
                    ifLeft = { error ->
                        // 失败路径：记录错误并返回
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
                        // 成功路径：生成 Token 并返回
                        val token = tokenService.generate(user.id.value)
                        val duration = System.currentTimeMillis() - startTime

                        logger.info(
                            "注册成功: userId=${user.id.value}, email=${user.email.value}, " +
                            "duration=${duration}ms, clientIp=$clientIp"
                        )

                        call.respond(HttpStatusCode.Created, user.toResponse(token))
                    }
                )

            } catch (e: Exception) {
                // 捕获反序列化错误或其他未预期的异常
                val duration = System.currentTimeMillis() - startTime
                logger.error(
                    "注册请求异常: clientIp=$clientIp, userAgent=${userAgent.take(100)}, " +
                    "duration=${duration}ms, error=${e.message}",
                    e
                )
                throw e // 重新抛出让全局异常处理器处理
            }
        }

        post("/login") {
            val startTime = System.currentTimeMillis()

            // 获取客户端信息（用于日志）
            val clientIp = call.request.local.remoteAddress
            val userAgent = call.request.headers["User-Agent"] ?: "Unknown"

            try {
                // 1. 接收并反序列化 JSON
                val request = call.receive<LoginRequest>()
                logger.info(
                    "收到登录请求: email=${request.email}, " +
                    "clientIp=$clientIp, userAgent=${userAgent.take(100)}"
                )

                // 2. 调用业务逻辑
                val result = loginUseCase(request.toCommand())

                // 3. 处理结果
                result.fold(
                    ifLeft = { error ->
                        // 失败路径：记录错误并返回
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
                        // 成功路径：生成 Token 并返回
                        val token = tokenService.generate(user.id.value)
                        val duration = System.currentTimeMillis() - startTime

                        logger.info(
                            "登录成功: userId=${user.id.value}, email=${user.email.value}, " +
                            "duration=${duration}ms, clientIp=$clientIp"
                        )

                        call.respond(HttpStatusCode.OK, user.toResponse(token))
                    }
                )

            } catch (e: Exception) {
                // 捕获反序列化错误或其他未预期的异常
                val duration = System.currentTimeMillis() - startTime
                logger.error(
                    "登录请求异常: clientIp=$clientIp, userAgent=${userAgent.take(100)}, " +
                    "duration=${duration}ms, error=${e.message}",
                    e
                )
                throw e // 重新抛出让全局异常处理器处理
            }
        }
    }
}