package com.connor.data.repository

import com.connor.data.db.dbQuery
import com.connor.data.db.schema.RefreshTokensTable
import com.connor.domain.model.RefreshToken
import com.connor.domain.model.UserId
import com.connor.domain.repository.RefreshTokenRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

class ExposedRefreshTokenRepository : RefreshTokenRepository {
    private val logger = LoggerFactory.getLogger(ExposedRefreshTokenRepository::class.java)

    override suspend fun save(token: RefreshToken): Unit = dbQuery {
        RefreshTokensTable.insert {
            it[id] = token.id
            it[tokenHash] = token.tokenHash
            it[userId] = token.userId.value
            it[familyId] = token.familyId
            it[expiresAt] = token.expiresAt
            it[isRevoked] = token.isRevoked
            it[createdAt] = token.createdAt
        }
        logger.debug("Refresh token saved: userId={}, familyId={}", token.userId.value, token.familyId)
    }

    override suspend fun findByTokenHash(tokenHash: String): RefreshToken? = dbQuery {
        RefreshTokensTable.selectAll()
            .where { RefreshTokensTable.tokenHash eq tokenHash }
            .singleOrNull()
            ?.toRefreshToken()
    }

    override suspend fun revokeByTokenHash(tokenHash: String): Unit = dbQuery {
        val now = System.currentTimeMillis()
        RefreshTokensTable.update({ RefreshTokensTable.tokenHash eq tokenHash }) {
            it[isRevoked] = true
            it[revokedAt] = now
        }
    }

    override suspend fun revokeFamily(familyId: String): Unit = dbQuery {
        val now = System.currentTimeMillis()
        val count = RefreshTokensTable.update({ RefreshTokensTable.familyId eq familyId }) {
            it[isRevoked] = true
            it[revokedAt] = now
        }
        logger.warn("Token family revoked: familyId={}, revokedCount={}", familyId, count)
    }

    override suspend fun revokeAllForUser(userId: UserId): Unit = dbQuery {
        val now = System.currentTimeMillis()
        val count = RefreshTokensTable.update({ RefreshTokensTable.userId eq userId.value }) {
            it[isRevoked] = true
            it[revokedAt] = now
        }
        logger.info("All refresh tokens revoked for user: userId={}, revokedCount={}", userId.value, count)
    }

    override suspend fun deleteExpired(): Unit = dbQuery {
        val now = System.currentTimeMillis()
        val count = RefreshTokensTable.deleteWhere {
            (RefreshTokensTable.expiresAt less now) and (RefreshTokensTable.isRevoked eq true)
        }
        if (count > 0) {
            logger.info("Expired refresh tokens cleaned up: count={}", count)
        }
    }

    override suspend fun revokeIfActive(tokenHash: String): Boolean = dbQuery {
        val now = System.currentTimeMillis()
        val affected = RefreshTokensTable.update({
            (RefreshTokensTable.tokenHash eq tokenHash) and (RefreshTokensTable.isRevoked eq false)
        }) {
            it[isRevoked] = true
            it[revokedAt] = now
        }
        affected > 0
    }

    override suspend fun findLatestRevokedInFamily(familyId: String): RefreshToken? = dbQuery {
        RefreshTokensTable.selectAll()
            .where {
                (RefreshTokensTable.familyId eq familyId) and (RefreshTokensTable.isRevoked eq true)
            }
            .orderBy(RefreshTokensTable.revokedAt to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toRefreshToken()
    }

    private fun ResultRow.toRefreshToken(): RefreshToken {
        return RefreshToken(
            id = this[RefreshTokensTable.id],
            tokenHash = this[RefreshTokensTable.tokenHash],
            userId = UserId(this[RefreshTokensTable.userId]),
            familyId = this[RefreshTokensTable.familyId],
            expiresAt = this[RefreshTokensTable.expiresAt],
            isRevoked = this[RefreshTokensTable.isRevoked],
            revokedAt = this[RefreshTokensTable.revokedAt],
            createdAt = this[RefreshTokensTable.createdAt]
        )
    }
}
