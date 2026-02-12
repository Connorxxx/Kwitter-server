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
     * 原子地将 token 从 active 状态撤销。
     * 仅当 token 当前未被撤销时才执行 UPDATE，返回是否成功。
     * 用于 token rotation 的竞态保护：并发请求中只有一个能成功。
     */
    suspend fun revokeIfActive(tokenHash: String): Boolean

    /**
     * 查找同family中最近被撤销的token
     * 用于 grace period 判断：如果旧token在宽限期内被重用，仍然允许
     */
    suspend fun findLatestRevokedInFamily(familyId: String): RefreshToken?
}
