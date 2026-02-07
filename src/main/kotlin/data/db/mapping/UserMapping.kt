package com.connor.data.db.mapping

import com.connor.data.db.schema.UsersTable
import com.connor.domain.model.Email
import com.connor.domain.model.PasswordHash
import com.connor.domain.model.User
import com.connor.domain.model.UserId
import org.jetbrains.exposed.sql.ResultRow

fun ResultRow.toDomain(): User {
    return User(
        // 还原 Value Classes
        id = UserId(this[UsersTable.id]),
        email = Email.unsafe(this[UsersTable.email]), // 数据库中的数据已验证，使用 unsafe
        passwordHash = PasswordHash(this[UsersTable.passwordHash]),
        displayName = this[UsersTable.displayName],
        bio = this[UsersTable.bio],
        createdAt = this[UsersTable.createdAt]
    )
}