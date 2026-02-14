package com.connor.data.db

import com.connor.data.db.schema.BlocksTable
import com.connor.data.db.schema.BookmarksTable
import com.connor.data.db.schema.ConversationsTable
import com.connor.data.db.schema.FollowsTable
import com.connor.data.db.schema.LikesTable
import com.connor.data.db.schema.MediaTable
import com.connor.data.db.schema.MessagesTable
import com.connor.data.db.schema.PostsTable
import com.connor.data.db.schema.RefreshTokensTable
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

    /**
     * 创建搜索向量和全文搜索索引（幂等操作）
     *
     * 使用 PostgreSQL Full-Text Search (FTS) 功能：
     * 1. 为 Posts 和 Users 表添加 search_vector tsvector 列
     * 2. 创建触发器自动更新 search_vector（INSERT/UPDATE 时）
     * 3. 创建 GIN 索引加速全文搜索
     * 4. 更新现有行的 search_vector 值
     */
    private fun JdbcTransaction.createSearchVectors() {
        try {
            val statements = listOf(
                // ========== Posts 表搜索向量 ==========

                // 1. 添加 tsvector 列
                "ALTER TABLE posts ADD COLUMN IF NOT EXISTS search_vector tsvector",

                // 2. 创建触发器函数（自动更新 search_vector）
                """
                CREATE OR REPLACE FUNCTION posts_search_vector_update() RETURNS trigger AS $$
                BEGIN
                    NEW.search_vector := to_tsvector('english', COALESCE(NEW.content, ''));
                    RETURN NEW;
                END
                $$ LANGUAGE plpgsql
                """.trimIndent(),

                // 3. 删除旧触发器（如果存在）
                "DROP TRIGGER IF EXISTS posts_search_vector_trigger ON posts",

                // 4. 创建触发器（INSERT 或 UPDATE 时自动调用）
                """
                CREATE TRIGGER posts_search_vector_trigger
                BEFORE INSERT OR UPDATE ON posts
                FOR EACH ROW EXECUTE FUNCTION posts_search_vector_update()
                """.trimIndent(),

                // 5. 更新现有行的 search_vector（避免 NULL 值）
                """
                UPDATE posts SET search_vector = to_tsvector('english', COALESCE(content, ''))
                WHERE search_vector IS NULL
                """.trimIndent(),

                // 6. 创建 GIN 索引（加速全文搜索）
                "CREATE INDEX IF NOT EXISTS idx_posts_search_vector ON posts USING GIN(search_vector)",

                // ========== Users 表搜索向量（加权搜索）==========

                // 1. 添加 tsvector 列
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS search_vector tsvector",

                // 2. 创建触发器函数（username=A, display_name=B, bio=C）
                """
                CREATE OR REPLACE FUNCTION users_search_vector_update() RETURNS trigger AS $$
                BEGIN
                    NEW.search_vector :=
                        setweight(to_tsvector('english', COALESCE(NEW.username, '')), 'A') ||
                        setweight(to_tsvector('english', COALESCE(NEW.display_name, '')), 'B') ||
                        setweight(to_tsvector('english', COALESCE(NEW.bio, '')), 'C');
                    RETURN NEW;
                END
                $$ LANGUAGE plpgsql
                """.trimIndent(),

                // 3. 删除旧触发器（如果存在）
                "DROP TRIGGER IF EXISTS users_search_vector_trigger ON users",

                // 4. 创建触发器
                """
                CREATE TRIGGER users_search_vector_trigger
                BEFORE INSERT OR UPDATE ON users
                FOR EACH ROW EXECUTE FUNCTION users_search_vector_update()
                """.trimIndent(),

                // 5. 更新现有行的 search_vector
                """
                UPDATE users SET search_vector =
                    setweight(to_tsvector('english', COALESCE(username, '')), 'A') ||
                    setweight(to_tsvector('english', COALESCE(display_name, '')), 'B') ||
                    setweight(to_tsvector('english', COALESCE(bio, '')), 'C')
                WHERE search_vector IS NULL
                """.trimIndent(),

                // 6. 创建 GIN 索引
                "CREATE INDEX IF NOT EXISTS idx_users_search_vector ON users USING GIN(search_vector)"
            )

            statements.forEach { sql ->
                try {
                    exec(sql)
                    logger.debug("搜索向量语句执行成功")
                } catch (e: Exception) {
                    // 忽略"已存在"类错误
                    if (e.message?.contains("already exists", ignoreCase = true) == true ||
                        e.message?.contains("duplicate", ignoreCase = true) == true) {
                        logger.debug("搜索向量已存在（跳过）: ${e.message}")
                    } else {
                        logger.warn("搜索向量创建警告: ${e.message}")
                        // 不抛出异常，FTS 功能失败不应阻止应用启动
                    }
                }
            }

            logger.info("搜索向量检查完成")
        } catch (e: Exception) {
            logger.error("搜索向量创建过程出错: ${e.message}", e)
            // 不抛出异常，让应用继续启动（搜索功能降级）
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
            val tables = listOf(UsersTable, PostsTable, MediaTable, LikesTable, BookmarksTable, FollowsTable, BlocksTable, RefreshTokensTable, ConversationsTable, MessagesTable)

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

            // 创建搜索向量和 FTS 索引（PostgreSQL Full-Text Search）
            createSearchVectors()
        }
    }
}

// 简单的配置类
data class DatabaseConfig(val url: String, val user: String, val password: String)
