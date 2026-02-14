package com.connor.data.db.schema

import org.jetbrains.exposed.v1.core.Table

object BlocksTable : Table("blocks") {
    val blockerId = varchar("blocker_id", 36).references(UsersTable.id)
    val blockedId = varchar("blocked_id", 36).references(UsersTable.id)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(blockerId, blockedId)

    init {
        index("idx_blocks_blocker", false, blockerId)
        index("idx_blocks_blocked", false, blockedId)
    }
}
