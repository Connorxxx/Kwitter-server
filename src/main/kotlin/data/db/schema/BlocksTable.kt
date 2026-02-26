package com.connor.data.db.schema

import org.jetbrains.exposed.v1.core.Table

object BlocksTable : Table("blocks") {
    val blockerId = long("blocker_id").references(UsersTable.id)
    val blockedId = long("blocked_id").references(UsersTable.id)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(blockerId, blockedId)

    init {
        index("idx_blocks_blocker", false, blockerId)
        index("idx_blocks_blocked", false, blockedId)
    }
}
