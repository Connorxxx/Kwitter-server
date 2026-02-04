package com.connor.features.auth

import com.connor.domain.failure.AuthError
import com.connor.domain.model.User
import com.connor.domain.usecase.RegisterCommand
import io.ktor.http.HttpStatusCode

fun RegisterRequest.toCommand() = RegisterCommand(
    email = this.email,
    password = this.password,
    displayName = this.displayName
)

// Domain Model -> DTO
fun User.toResponse(token: String? = null) = UserResponse(
    id = this.id.value,
    email = this.email.value,
    displayName = this.displayName,
    token = token
)

// 关键：将业务错误映射为 HTTP 状态码和错误信息
fun AuthError.toHttpError(): Pair<HttpStatusCode, ErrorResponse> = when (this) {
    is AuthError.UserAlreadyExists ->
        HttpStatusCode.Conflict to ErrorResponse("USER_EXISTS", "Email $email is already registered.")
    is AuthError.InvalidCredentials ->
        HttpStatusCode.Unauthorized to ErrorResponse("AUTH_FAILED", "Invalid email or password.")
    is AuthError.InvalidFormat ->
        HttpStatusCode.BadRequest to ErrorResponse("INVALID_FORMAT", reason)
}