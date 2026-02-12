package com.connor.domain.service

/**
 * Auth token timing configuration (domain concern: expiry is business policy).
 */
data class AuthTokenConfig(
    val accessTokenExpiresInMs: Long,
    val refreshTokenExpiresInMs: Long,
    val refreshTokenGracePeriodMs: Long
)
