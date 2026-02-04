package com.connor.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.data.db.dbQuery
import com.connor.data.db.mapping.toDomain
import com.connor.data.db.schema.UsersTable
import com.connor.domain.failure.AuthError
import com.connor.domain.model.Email
import com.connor.domain.model.User
import com.connor.domain.repository.UserRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import java.sql.SQLException

class ExposedUserRepository : UserRepository {
    override suspend fun save(user: User): Either<AuthError, User> = dbQuery {
        try {
            // Exposed DSL: 插入操作
            UsersTable.insert {
                it[id] = user.id.value
                it[email] = user.email.value
                it[passwordHash] = user.passwordHash.value
                it[displayName] = user.displayName
                it[bio] = user.bio
                it[createdAt] = user.createdAt
            }
            // 插入成功，返回 Right(User)
            user.right()

        } catch (e: SQLException) {
            // 捕获数据库层面的错误，转换为 Domain层面的错误
            // 在 PostgreSQL 中，SQLState 23505 代表 Unique Violation (重复键)
            if (e.sqlState == "23505") {
                AuthError.UserAlreadyExists(user.email.value).left()
            } else {
                throw e // 其他未预料的数据库错误，直接抛出，让 StatusPages 处理成 500
            }
        }
    }

    override suspend fun findByEmail(email: Email): User? = dbQuery {
        // Exposed DSL: 查询操作
        UsersTable.select(UsersTable.email eq email.value)
            .singleOrNull()?.toDomain() // 期望只有一个结果或没有
    }

    override suspend fun existsByEmail(email: Email): Boolean = dbQuery {
        // 优化查询：只查 Count 而不是取出数据
        UsersTable.select(UsersTable.email eq email.value).count() > 0
    }
}