package com.connor.data.db.schema

import org.jetbrains.exposed.v1.core.Table

object RefreshTokensTable : Table("refresh_tokens") {
    val id = long("id")
    val tokenHash = varchar("token_hash", 128).uniqueIndex()
    val userId = long("user_id").index()
    val familyId = long("family_id").index()
    val expiresAt = long("expires_at")
    val isRevoked = bool("is_revoked").default(false)
    val revokedAt = long("revoked_at").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}
