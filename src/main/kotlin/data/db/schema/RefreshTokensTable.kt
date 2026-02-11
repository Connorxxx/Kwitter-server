package com.connor.data.db.schema

import org.jetbrains.exposed.v1.core.Table

object RefreshTokensTable : Table("refresh_tokens") {
    val id = varchar("id", 36)
    val tokenHash = varchar("token_hash", 128).uniqueIndex()
    val userId = varchar("user_id", 36).index()
    val familyId = varchar("family_id", 36).index()
    val expiresAt = long("expires_at")
    val isRevoked = bool("is_revoked").default(false)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}
