package com.connor.domain.repository

import com.connor.domain.model.RefreshToken
import com.connor.domain.model.UserId

interface RefreshTokenRepository {
    suspend fun save(token: RefreshToken)
    suspend fun findByTokenHash(tokenHash: String): RefreshToken?
    suspend fun revokeByTokenHash(tokenHash: String)
    suspend fun revokeFamily(familyId: String)
    suspend fun revokeAllForUser(userId: UserId)
    suspend fun deleteExpired()

    /**
     * 查找同family中最近被撤销的token的撤销时间
     * 用于 grace period 判断：如果旧token在宽限期内被重用，仍然允许
     */
    suspend fun findLatestRevokedInFamily(familyId: String): RefreshToken?
}
