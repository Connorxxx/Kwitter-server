package com.connor.domain.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.AuthError

@JvmInline value class UserId(val value: String)

@JvmInline
value class Email private constructor(val value: String) {
    companion object {
        // 用户输入验证：返回 Either
        operator fun invoke(value: String): Either<AuthError, Email> {
            val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
            return if (value.matches(emailRegex)) {
                Email(value).right()
            } else {
                AuthError.InvalidEmail(value).left()
            }
        }

        // 内部使用：从数据库等可信来源构造（已验证过）
        fun unsafe(value: String): Email = Email(value)
    }
}

@JvmInline value class PasswordHash(val value: String)

data class User(
    val id: UserId,
    val email: Email,
    val passwordHash: PasswordHash, // 领域模型只持有 Hash，不持有明文
    val displayName: String,
    val bio: String = "",
    val avatarUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)