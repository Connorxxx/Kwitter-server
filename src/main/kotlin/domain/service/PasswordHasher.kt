package com.connor.domain.service

import arrow.core.Either
import com.connor.domain.failure.AuthError
import com.connor.domain.model.PasswordHash

interface PasswordHasher {
    /**
     * 验证密码强度
     * @return Either.Left(AuthError.WeakPassword) 如果密码不符合要求
     */
    fun validate(rawPassword: String): Either<AuthError, Unit>

    /**
     * 哈希密码（假设已验证）
     */
    fun hash(rawPassword: String): PasswordHash

    /**
     * 验证密码是否匹配
     */
    fun verify(rawPassword: String, hash: PasswordHash): Boolean
}