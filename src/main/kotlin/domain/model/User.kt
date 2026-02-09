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
            val normalized = value.trim().lowercase()  // ✅ 规范化：防止 "A@B.com" 和 "a@b.com" 被视为不同账号
            val emailRegex = "^[a-z0-9+_.-]+@[a-z0-9.-]+\\.[a-z]{2,}$".toRegex()
            return if (normalized.matches(emailRegex)) {
                Email(normalized).right()
            } else {
                AuthError.InvalidEmail(value).left()  // 原始值用于错误提示
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
    val username: Username, // 唯一标识符，用于 @ 和显示
    val displayName: DisplayName,
    val bio: Bio = Bio.unsafe(""),
    val avatarUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)