package com.connor.domain.service

/**
 * JWT Access Token issuer port.
 * Domain defines the contract; infrastructure provides JWT implementation.
 */
interface TokenIssuer {
    fun generate(userId: String, displayName: String, username: String): String
}
