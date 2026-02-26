package com.connor.features.auth

import com.connor.domain.failure.AuthError
import com.connor.domain.model.User
import com.connor.domain.usecase.RegisterCommand
import com.connor.domain.usecase.TokenPair
import io.ktor.http.HttpStatusCode

/**
 * HTTP Request DTO -> Domain Command
 */
fun RegisterRequest.toCommand() = RegisterCommand(
    email = this.email,
    password = this.password,
    displayName = this.displayName
)

fun LoginRequest.toCommand() = com.connor.domain.usecase.LoginCommand(
    email = this.email,
    password = this.password
)

/**
 * Domain Model -> HTTP Response DTO
 */
fun User.toResponse(tokenPair: TokenPair) = UserResponse(
    id = this.id.value.toString(),
    email = this.email.value,
    username = this.username.value,
    displayName = this.displayName.value,
    bio = this.bio.value,
    avatarUrl = this.avatarUrl,
    createdAt = this.createdAt,
    token = tokenPair.accessToken,
    refreshToken = tokenPair.refreshToken,
    expiresIn = tokenPair.expiresIn
)

fun TokenPair.toResponse() = TokenResponse(
    token = this.accessToken,
    refreshToken = this.refreshToken,
    expiresIn = this.expiresIn
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

    is AuthError.InvalidDisplayName ->
        HttpStatusCode.BadRequest to ErrorResponse(
            code = "INVALID_DISPLAY_NAME",
            message = reason
        )

    is AuthError.RefreshTokenExpired ->
        HttpStatusCode.Unauthorized to ErrorResponse(
            code = "REFRESH_TOKEN_EXPIRED",
            message = "Refresh token 已过期，请重新登录"
        )

    is AuthError.RefreshTokenRevoked ->
        HttpStatusCode.Unauthorized to ErrorResponse(
            code = "REFRESH_TOKEN_REVOKED",
            message = "Refresh token 已被撤销，请重新登录"
        )

    is AuthError.RefreshTokenNotFound ->
        HttpStatusCode.Unauthorized to ErrorResponse(
            code = "REFRESH_TOKEN_INVALID",
            message = "Refresh token 无效"
        )

    is AuthError.TokenFamilyReused ->
        HttpStatusCode.Unauthorized to ErrorResponse(
            code = "TOKEN_REUSE_DETECTED",
            message = "检测到异常登录活动，所有会话已失效，请重新登录"
        )

    is AuthError.StaleRefreshToken ->
        HttpStatusCode(409, "Conflict") to ErrorResponse(
            code = "STALE_REFRESH_TOKEN",
            message = "Token 已被并发请求轮换，请使用最新 token 重试"
        )

    is AuthError.SessionRevoked ->
        HttpStatusCode.Unauthorized to ErrorResponse(
            code = "SESSION_REVOKED",
            message = "会话已失效，请重新登录"
        )
}
