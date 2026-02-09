package com.connor.data.db

import com.connor.data.db.schema.BookmarksTable
import com.connor.data.db.schema.FollowsTable
import com.connor.data.db.schema.LikesTable
import com.connor.data.db.schema.MediaTable
import com.connor.data.db.schema.PostsTable
import com.connor.data.db.schema.UsersTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("DatabaseFactory")

// 这是一个通用的辅助函数，所有 Repo 都会用到
suspend fun <T> dbQuery(block: suspend () -> T): T =
    suspendTransaction { block() }

object DatabaseFactory {

    /**
     * 创建数据库索引（幂等操作）
     *
     * 使用原生 SQL 的 CREATE INDEX IF NOT EXISTS 确保：
     * 1. 如果索引不存在，则创建
     * 2. 如果索引已存在，则跳过（不报错）
     * 3. 可以安全地重复执行
     */
    private fun JdbcTransaction.createIndices() {
        try {
            // Posts 表的性能优化索引
            val indices = listOf(
                // 索引1：查询回复列表（WHERE parent_id = ? ORDER BY created_at）
                """
                CREATE INDEX IF NOT EXISTS idx_posts_parent_created
                ON posts (parent_id, created_at)
                """.trimIndent(),

                // 索引2：查询用户的 Posts（WHERE author_id = ? AND parent_id IS NULL）
                """
                CREATE INDEX IF NOT EXISTS idx_posts_author_parent
                ON posts (author_id, parent_id)
                """.trimIndent()
            )

            indices.forEach { sql ->
                try {
                    exec(sql)
                    logger.debug("索引创建成功或已存在")
                } catch (e: Exception) {
                    // PostgreSQL: "relation already exists"
                    // H2: "Index already exists"
                    if (e.message?.contains("already exists", ignoreCase = true) == true ||
                        e.message?.contains("duplicate", ignoreCase = true) == true) {
                        logger.debug("索引已存在（跳过）: ${e.message}")
                    } else {
                        logger.warn("索引创建警告: ${e.message}")
                        // 不抛出异常，索引创建失败不应阻止应用启动
                        // 最坏情况是性能稍差，但功能可用
                    }
                }
            }

            logger.info("数据库索引检查完成")
        } catch (e: Exception) {
            logger.error("索引创建过程出错: ${e.message}", e)
            // 不抛出异常，让应用继续启动
        }
    }

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
        // 所以我们需要分三步：创建表 + 添加缺失的列 + 创建索引
        transaction {
            val tables = listOf(UsersTable, PostsTable, MediaTable, LikesTable, BookmarksTable, FollowsTable)

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

            // 最后创建索引（使用 CREATE INDEX IF NOT EXISTS 确保幂等性）
            createIndices()
        }
    }
}

// 简单的配置类
data class DatabaseConfig(val url: String, val user: String, val password: String)
