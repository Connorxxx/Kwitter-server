package com.connor.data.db

import com.connor.data.db.schema.BookmarksTable
import com.connor.data.db.schema.LikesTable
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
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("DatabaseFactory")

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

        // 3. 自动建表和添加缺失的列
        // 注意：SchemaUtils.create() 只创建不存在的表，不会为现有表添加新列
        // 所以我们需要分两步：创建表 + 添加缺失的列
        transaction {
            val tables = listOf(UsersTable, PostsTable, MediaTable, LikesTable, BookmarksTable)

            // 先创建不存在的表
            SchemaUtils.create(*tables.toTypedArray())

            // 然后添加缺失的列（对于已有表的环保升级）
            // 注意：这里不catch异常，如果DDL失败则fail fast，避免隐藏数据库升级问题
            val addColumnsStatements = SchemaUtils.addMissingColumnsStatements(*tables.toTypedArray())
            if (addColumnsStatements.isNotEmpty()) {
                addColumnsStatements.forEach { statement ->
                    try {
                        exec(statement)
                    } catch (e: Exception) {
                        // 如果是"column already exists"则忽略（Exposed未在addMissingColumnsStatements中过滤）
                        if (e.message?.contains("already exists", ignoreCase = true) == true ||
                            e.message?.contains("duplicate column", ignoreCase = true) == true) {
                            logger.debug("列已存在（跳过）: ${e.message}")
                        } else {
                            // 其他DDL错误则抛出，阻止应用启动
                            throw e
                        }
                    }
                }
                logger.info("数据库列更新完成：添加了缺失的列")
            }
        }
    }
}

// 简单的配置类
data class DatabaseConfig(val url: String, val user: String, val password: String)