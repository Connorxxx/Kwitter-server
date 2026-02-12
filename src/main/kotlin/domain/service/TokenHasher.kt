package com.connor.domain.service

/**
 * Refresh Token generation and hashing port.
 * Domain defines the contract; infrastructure provides SecureRandom + SHA-256.
 */
interface TokenHasher {
    fun generateToken(): String
    fun hashToken(token: String): String
}
