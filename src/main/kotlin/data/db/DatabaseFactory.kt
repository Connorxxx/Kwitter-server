package com.connor.data.db

import com.connor.data.db.schema.MediaTable
import com.connor.data.db.schema.PostsTable
import com.connor.data.db.schema.UsersTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

// 这是一个通用的辅助函数，所有 Repo 都会用到
suspend fun <T> dbQuery(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }

object DatabaseFactory {

    fun init(config: DatabaseConfig) {
        // 1. 配置 HikariCP 连接池
        val hikariConfig = HikariConfig().apply {
            driverClassName = "org.postgresql.Driver" // 或者 "org.h2.Driver"
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }

        val dataSource = HikariDataSource(hikariConfig)

        // 2. 连接 Exposed
        Database.connect(dataSource)

        // 3. 自动建表 (类似 Room 的 auto-migration，生产环境建议用 Flyway)
        transaction {
            SchemaUtils.create(UsersTable, PostsTable, MediaTable)
        }
    }
}

// 简单的配置类
data class DatabaseConfig(val url: String, val user: String, val password: String)