package com.connor.data.db.mapping

import com.connor.data.db.schema.UsersTable
import com.connor.domain.model.*
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toDomain(): User {
    return User(
        // 还原 Value Classes
        id = UserId(this[UsersTable.id]),
        email = Email.unsafe(this[UsersTable.email]), // 数据库中的数据已验证，使用 unsafe
        passwordHash = PasswordHash(this[UsersTable.passwordHash]),
        username = Username.unsafe(this[UsersTable.username]),
        displayName = DisplayName.unsafe(this[UsersTable.displayName]),
        bio = Bio.unsafe(this[UsersTable.bio]),
        avatarUrl = this[UsersTable.avatarUrl],
        dmPermission = try {
            DmPermission.valueOf(this[UsersTable.dmPermission])
        } catch (_: IllegalArgumentException) {
            DmPermission.EVERYONE
        },
        passwordChangedAt = this[UsersTable.passwordChangedAt],
        createdAt = this[UsersTable.createdAt]
    )
}
