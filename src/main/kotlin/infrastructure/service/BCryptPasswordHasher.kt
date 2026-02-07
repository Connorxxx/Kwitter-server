package com.connor.infrastructure.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.AuthError
import com.connor.domain.model.PasswordHash
import com.connor.domain.service.PasswordHasher
import org.mindrot.jbcrypt.BCrypt

class BCryptPasswordHasher : PasswordHasher {
    override fun validate(rawPassword: String): Either<AuthError, Unit> {
        return when {
            rawPassword.length < 8 ->
                AuthError.WeakPassword("密码至少需要 8 位字符").left()
            rawPassword.length > 72 ->
                AuthError.WeakPassword("密码不能超过 72 位字符（BCrypt 限制）").left()
            !rawPassword.any { it.isDigit() } ->
                AuthError.WeakPassword("密码必须包含至少一个数字").left()
            !rawPassword.any { it.isLetter() } ->
                AuthError.WeakPassword("密码必须包含至少一个字母").left()
            else -> Unit.right()
        }
    }

    override fun hash(rawPassword: String): PasswordHash {
        val salt = BCrypt.gensalt(12)
        val hashedPassword = BCrypt.hashpw(rawPassword, salt)
        return PasswordHash(hashedPassword)
    }

    override fun verify(rawPassword: String, hash: PasswordHash): Boolean {
        return try {
            BCrypt.checkpw(rawPassword, hash.value)
        } catch (e: Exception) {
            false
        }
    }
}
