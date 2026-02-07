package com.connor.features.auth

import com.connor.domain.failure.AuthError
import com.connor.domain.model.User
import com.connor.domain.usecase.RegisterCommand
import io.ktor.http.HttpStatusCode

/**
 * HTTP Request DTO -> Domain Command
 */
fun RegisterRequest.toCommand() = RegisterCommand(
    email = this.email,
    password = this.password,
    displayName = this.displayName
)

/**
 * Domain Model -> HTTP Response DTO
 */
fun User.toResponse(token: String? = null) = UserResponse(
    id = this.id.value,
    email = this.email.value,
    displayName = this.displayName,
    token = token
)

/**
 * 将业务错误映射为 HTTP 状态码和错误响应
 * 这是 Transport 层的职责：协议转换
 */
fun AuthError.toHttpError(): Pair<HttpStatusCode, ErrorResponse> = when (this) {
    is AuthError.UserAlreadyExists ->
        HttpStatusCode.Conflict to ErrorResponse(
            code = "USER_EXISTS",
            message = "邮箱 $email 已被注册"
        )

    is AuthError.InvalidCredentials ->
        HttpStatusCode.Unauthorized to ErrorResponse(
            code = "AUTH_FAILED",
            message = "邮箱或密码错误"
        )

    is AuthError.InvalidEmail ->
        HttpStatusCode.BadRequest to ErrorResponse(
            code = "INVALID_EMAIL",
            message = "邮箱格式不正确: $value"
        )

    is AuthError.WeakPassword ->
        HttpStatusCode.BadRequest to ErrorResponse(
            code = "WEAK_PASSWORD",
            message = reason
        )
}