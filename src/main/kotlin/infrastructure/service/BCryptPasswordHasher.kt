package com.connor.infrastructure.service

import com.connor.domain.model.PasswordHash
import com.connor.domain.service.PasswordHasher
import org.mindrot.jbcrypt.BCrypt

class BCryptPasswordHasher : PasswordHasher {
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
