package com.connor.features.auth

import com.connor.core.security.TokenService
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
// 你的 UseCase 和 Arrow 依赖
import com.connor.domain.usecase.RegisterUseCase

fun Route.authRoutes(
    registerUseCase: RegisterUseCase,
    tokenService: TokenService // 假设你有一个简单的 Token 生成器
) {
    route("/v1/auth") {

        post("/register") {
            // 1. 接收并反序列化 JSON (类似 Android 的 Gson/Moshi)
            // 如果 JSON 格式不对，Ktor 会自动抛出 BadRequest，由 StatusPages 插件捕获
            val request = call.receive<RegisterRequest>()

            // 2. 调用业务逻辑
            // 这里的 invoke 返回 Either<AuthError, User>
            val result = registerUseCase(request.toCommand())

            // 3. 处理结果 (Fold 是函数式编程处理结果的标准姿势)
            result.fold(
                ifLeft = { error ->
                    // 失败路径：映射业务错误 -> HTTP 响应
                    val (status, body) = error.toHttpError()
                    call.respond(status, body)
                },
                ifRight = { user ->
                    // 成功路径：生成 Token -> 返回 201 Created
                    val token = tokenService.generate(user.id.value)
                    call.respond(HttpStatusCode.Created, user.toResponse(token))
                }
            )
        }
    }
}