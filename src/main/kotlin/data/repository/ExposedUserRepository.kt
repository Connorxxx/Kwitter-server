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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.sql.SQLException

class ExposedUserRepository : UserRepository {
    private val logger = LoggerFactory.getLogger(ExposedUserRepository::class.java)

    override suspend fun save(user: User): Either<AuthError, User> = dbQuery {
        try {
            logger.debug("准备插入用户: userId=${user.id.value}, email=${user.email.value}")

            UsersTable.insert {
                it[id] = user.id.value
                it[email] = user.email.value
                it[passwordHash] = user.passwordHash.value
                it[displayName] = user.displayName
                it[bio] = user.bio
                it[avatarUrl] = user.avatarUrl
                it[createdAt] = user.createdAt
            }

            logger.info("用户插入成功: userId=${user.id.value}, email=${user.email.value}")
            user.right()

        } catch (e: SQLException) {
            // 数据库层面的唯一性约束违反
            // PostgreSQL: 23505, MySQL: 23000
            logger.error("数据库错误: sqlState=${e.sqlState}, message=${e.message}, email=${user.email.value}")

            when {
                e.sqlState == "23505" || e.sqlState == "23000" -> {
                    // 检查约束名称，精确映射
                    // PostgreSQL约束名格式: users_email_key
                    val constraintName = e.message?.lowercase() ?: ""
                    when {
                        constraintName.contains("email") -> {
                            logger.warn("邮箱已存在: email=${user.email.value}, constraint=$constraintName")
                            AuthError.UserAlreadyExists(user.email.value).left()
                        }
                        else -> {
                            logger.warn("未预期的唯一约束冲突: constraint=$constraintName", e)
                            throw e  // 未知约束走500路径
                        }
                    }
                }
                else -> {
                    logger.error("未知数据库错误: sqlState=${e.sqlState}, errorCode=${e.errorCode}", e)
                    throw e // 其他未预料的数据库错误，抛出让全局异常处理
                }
            }
        }
    }

    override suspend fun findByEmail(email: Email): User? = dbQuery {
        logger.debug("查询用户: email=${email.value}")

        val user = UsersTable.selectAll()
            .where { UsersTable.email eq email.value }
            .singleOrNull()
            ?.toDomain()

        if (user != null) {
            logger.debug("用户查询成功: userId=${user.id.value}, email=${email.value}")
        } else {
            logger.debug("用户不存在: email=${email.value}")
        }

        user
    }
}
