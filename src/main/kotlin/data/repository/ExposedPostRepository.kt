package com.connor.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.data.db.dbQuery
import com.connor.data.db.mapping.toDomain
import com.connor.data.db.mapping.toMediaAttachment
import com.connor.data.db.mapping.toPost
import com.connor.data.db.mapping.toPostStats
import com.connor.data.db.schema.MediaTable
import com.connor.data.db.schema.PostsTable
import com.connor.data.db.schema.UsersTable
import com.connor.domain.failure.PostError
import com.connor.domain.model.*
import com.connor.domain.repository.PostRepository
import com.connor.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Post Repository 的 Exposed 实现
 *
 * 职责：
 * - 将领域模型映射到数据库表
 * - 处理复杂查询（JOIN、分页）
 * - 返回 Either 处理业务错误
 */
class ExposedPostRepository(
    private val userRepository: UserRepository
) : PostRepository {
    private val logger = LoggerFactory.getLogger(ExposedPostRepository::class.java)

    override suspend fun create(post: Post): Either<PostError, Post> = dbQuery {
        try {
            logger.debug("准备插入 Post: postId=${post.id.value}, authorId=${post.authorId.value}")

            // 1. 插入 Post 主体
            PostsTable.insert {
                it[id] = post.id.value
                it[authorId] = post.authorId.value
                it[content] = post.content.value
                it[parentId] = post.parentId?.value
                it[createdAt] = post.createdAt
                it[updatedAt] = post.updatedAt
                it[replyCount] = 0
                it[likeCount] = 0
                it[viewCount] = 0
            }

            // 2. 插入媒体附件
            post.media.forEach { media ->
                MediaTable.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[postId] = post.id.value
                    it[url] = media.url.value
                    it[type] = media.type.name
                    it[order] = media.order
                }
            }

            // 3. 如果是回复，更新父 Post 的回复数
            post.parentId?.let { parentId ->
                PostsTable.update({ PostsTable.id eq parentId.value }) {
                    it[replyCount] = replyCount + 1
                }
            }

            logger.info("Post 插入成功: postId=${post.id.value}")
            post.right()

        } catch (e: Exception) {
            logger.error("Post 插入失败: postId=${post.id.value}, error=${e.message}", e)
            throw e
        }
    }

    override suspend fun findById(postId: PostId): Either<PostError, Post> = dbQuery {
        logger.debug("查询 Post: postId=${postId.value}")

        val postRow = PostsTable.selectAll()
            .where { PostsTable.id eq postId.value }
            .singleOrNull()

        if (postRow == null) {
            logger.debug("Post 不存在: postId=${postId.value}")
            return@dbQuery PostError.PostNotFound(postId).left()
        }

        // 查询媒体附件
        val media = MediaTable.selectAll()
            .where { MediaTable.postId eq postId.value }
            .orderBy(MediaTable.order to SortOrder.ASC)
            .map { it.toMediaAttachment() }

        val post = postRow.toPost().copy(media = media)
        logger.debug("Post 查询成功: postId=${postId.value}")
        post.right()
    }

    override suspend fun findDetailById(postId: PostId): Either<PostError, PostDetail> = dbQuery {
        logger.debug("查询 Post 详情: postId=${postId.value}")

        // 使用 JOIN 一次性查询 Post + Author
        val query = (PostsTable innerJoin UsersTable)
            .select(PostsTable.columns + UsersTable.columns)
            .where { PostsTable.id eq postId.value }

        val row = query.singleOrNull()
        if (row == null) {
            logger.debug("Post 不存在: postId=${postId.value}")
            return@dbQuery PostError.PostNotFound(postId).left()
        }

        // 映射 Post
        val media = MediaTable.selectAll()
            .where { MediaTable.postId eq postId.value }
            .orderBy(MediaTable.order to SortOrder.ASC)
            .map { it.toMediaAttachment() }
        val post = row.toPost().copy(media = media)

        // 映射 Author
        val author = row.toDomain()

        // 映射 Stats
        val stats = row.toPostStats()

        // 暂不查询父 Post 的详细信息（避免递归查询复杂度）
        // 客户端可以通过 parentId 单独查询父 Post
        val detail = PostDetail(
            post = post,
            author = author,
            stats = stats,
            parentPost = null
        )

        logger.debug("Post 详情查询成功: postId=${postId.value}")
        detail.right()
    }

    override fun findByAuthor(authorId: UserId, limit: Int, offset: Int): Flow<PostDetail> = flow {
        val details = dbQuery {
            logger.debug("查询用户 Posts: authorId=${authorId.value}, limit=$limit, offset=$offset")

            // 查询顶层 Posts（不包括回复）
            val query = (PostsTable innerJoin UsersTable)
                .select(PostsTable.columns + UsersTable.columns)
                .where {
                    (PostsTable.authorId eq authorId.value) and
                            (PostsTable.parentId.isNull())
                }
                .orderBy(PostsTable.createdAt to SortOrder.DESC)
                .limit(limit).offset(offset.toLong())

            query.map { row -> row.toPostDetailWithMedia() }
        }

        details.forEach { detail ->
            emit(detail)
        }
    }

    override fun findReplies(parentId: PostId, limit: Int, offset: Int): Flow<PostDetail> = flow {
        val details = dbQuery {
            logger.debug("查询回复: parentId=${parentId.value}, limit=$limit, offset=$offset")

            val query = (PostsTable innerJoin UsersTable)
                .select(PostsTable.columns + UsersTable.columns)
                .where { PostsTable.parentId eq parentId.value }
                .orderBy(PostsTable.createdAt to SortOrder.ASC) // 回复按时间正序
                .limit(limit).offset(offset.toLong())

            query.map { row -> row.toPostDetailWithMedia() }
        }

        details.forEach { detail ->
            emit(detail)
        }
    }

    override fun findTimeline(limit: Int, offset: Int): Flow<PostDetail> = flow {
        val details = dbQuery {
            logger.debug("查询时间线: limit=$limit, offset=$offset")

            // 查询所有顶层 Posts（不包括回复）
            val query = (PostsTable innerJoin UsersTable)
                .select(PostsTable.columns + UsersTable.columns)
                .where { PostsTable.parentId.isNull() }
                .orderBy(PostsTable.createdAt to SortOrder.DESC)
                .limit(limit).offset(offset.toLong())

            query.map { row -> row.toPostDetailWithMedia() }
        }

        details.forEach { detail ->
            emit(detail)
        }
    }

    /**
     * 将 ResultRow 映射为 PostDetail（包含媒体附件）
     */
    private fun ResultRow.toPostDetailWithMedia(): PostDetail {
        val postId = PostId(this[PostsTable.id])
        val media = MediaTable.selectAll()
            .where { MediaTable.postId eq postId.value }
            .orderBy(MediaTable.order to SortOrder.ASC)
            .map { it.toMediaAttachment() }

        val post = this.toPost().copy(media = media)
        val author = this.toDomain()
        val stats = this.toPostStats()

        return PostDetail(post, author, stats, parentPost = null)
    }

    override suspend fun delete(postId: PostId): Either<PostError, Unit> = dbQuery {
        logger.debug("删除 Post: postId=${postId.value}")

        // 先检查 Post 是否存在
        val exists = PostsTable.selectAll()
            .where { PostsTable.id eq postId.value }
            .singleOrNull() != null

        if (!exists) {
            logger.debug("Post 不存在，无法删除: postId=${postId.value}")
            return@dbQuery PostError.PostNotFound(postId).left()
        }

        // 删除媒体附件
        MediaTable.deleteWhere { MediaTable.postId eq postId.value }

        // 删除 Post
        PostsTable.deleteWhere { PostsTable.id eq postId.value }

        logger.info("Post 删除成功: postId=${postId.value}")
        Unit.right()
    }

    override suspend fun updateStats(postId: PostId, stats: PostStats): Either<PostError, Unit> = dbQuery {
        logger.debug("更新 Post 统计: postId=${postId.value}")

        val updated = PostsTable.update({ PostsTable.id eq postId.value }) {
            it[replyCount] = stats.replyCount
            it[likeCount] = stats.likeCount
            it[viewCount] = stats.viewCount
        }

        if (updated == 0) {
            logger.debug("Post 不存在，无法更新统计: postId=${postId.value}")
            return@dbQuery PostError.PostNotFound(postId).left()
        }

        logger.info("Post 统计更新成功: postId=${postId.value}")
        Unit.right()
    }
}
