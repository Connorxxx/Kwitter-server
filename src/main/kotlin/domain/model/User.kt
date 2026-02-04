package com.connor.domain.model

@JvmInline value class UserId(val value: String)
@JvmInline value class Email(val value: String)
@JvmInline value class PasswordHash(val value: String)

data class User(
    val id: UserId,
    val email: Email,
    val passwordHash: PasswordHash, // 领域模型只持有 Hash，不持有明文
    val displayName: String,
    val bio: String = "",
    val createdAt: Long = System.currentTimeMillis()
)