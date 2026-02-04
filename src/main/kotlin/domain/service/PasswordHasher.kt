package com.connor.domain.service

import com.connor.domain.model.PasswordHash

interface PasswordHasher {
    fun hash(rawPassword: String): PasswordHash
    fun verify(rawPassword: String, hash: PasswordHash): Boolean
}