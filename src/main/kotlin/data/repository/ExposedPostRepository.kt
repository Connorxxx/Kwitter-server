package com.connor.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.data.db.dbQuery
import com.connor.data.db.mapping.toDomain
import com.connor.data.db.mapping.toMediaAttachment
import com.connor.data.db.mapping.toPost
import com.connor.data.db.mapping.toPostStats
import com.connor.data.db.schema.BookmarksTable
import com.connor.data.db.schema.LikesTable
import com.connor.data.db.schema.MediaTable
import com.connor.data.db.schema.PostsTable
import com.connor.data.db.schema.UsersTable
import com.connor.domain.failure.BookmarkError
import com.connor.domain.failure.LikeError
import com.connor.domain.failure.PostError
import com.connor.domain.model.*
import com.connor.domain.repository.PostRepository
import com.connor.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.*
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
class ExposedPostRepository : PostRepository {
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

            // 查询顶层 Posts（不包括回复）- 多查询 1 条用于判断 hasMore
            val query = (PostsTable innerJoin UsersTable)
                .select(PostsTable.columns + UsersTable.columns)
                .where {
                    (PostsTable.authorId eq authorId.value) and
                            (PostsTable.parentId.isNull())
                }
                .orderBy(PostsTable.createdAt to SortOrder.DESC)
                .limit(limit + 1).offset(offset.toLong())

            val rows = query.toList()

            // 批量查询媒体附件
            val postIds = rows.map { PostId(it[PostsTable.id]) }
            val mediaMap = batchLoadMedia(postIds)

            rows.map { row -> row.toPostDetailWithMedia(mediaMap) }
        }

        details.forEach { detail ->
            emit(detail)
        }
    }

    override fun findReplies(parentId: PostId, limit: Int, offset: Int): Flow<PostDetail> = flow {
        val details = dbQuery {
            logger.debug("查询回复: parentId=${parentId.value}, limit=$limit, offset=$offset")

            // 多查询 1 条用于判断 hasMore
            val query = (PostsTable innerJoin UsersTable)
                .select(PostsTable.columns + UsersTable.columns)
                .where { PostsTable.parentId eq parentId.value }
                .orderBy(PostsTable.createdAt to SortOrder.ASC) // 回复按时间正序
                .limit(limit + 1).offset(offset.toLong())

            val rows = query.toList()

            // 批量查询媒体附件
            val postIds = rows.map { PostId(it[PostsTable.id]) }
            val mediaMap = batchLoadMedia(postIds)

            rows.map { row -> row.toPostDetailWithMedia(mediaMap) }
        }

        details.forEach { detail ->
            emit(detail)
        }
    }

    override fun findTimeline(limit: Int, offset: Int): Flow<PostDetail> = flow {
        val details = dbQuery {
            logger.debug("查询时间线: limit=$limit, offset=$offset")

            // 查询所有顶层 Posts（不包括回复）- 多查询 1 条用于判断 hasMore
            val query = (PostsTable innerJoin UsersTable)
                .select(PostsTable.columns + UsersTable.columns)
                .where { PostsTable.parentId.isNull() }
                .orderBy(PostsTable.createdAt to SortOrder.DESC)
                .limit(limit + 1).offset(offset.toLong())

            val rows = query.toList()

            // 批量查询媒体附件
            val postIds = rows.map { PostId(it[PostsTable.id]) }
            val mediaMap = batchLoadMedia(postIds)

            rows.map { row -> row.toPostDetailWithMedia(mediaMap) }
        }

        details.forEach { detail ->
            emit(detail)
        }
    }

    /**
     * 批量查询多个 Post 的媒体附件（解决 N+1 问题）
     * @return Map<PostId, List<MediaAttachment>>
     */
    private fun batchLoadMedia(postIds: List<PostId>): Map<PostId, List<MediaAttachment>> {
        if (postIds.isEmpty()) return emptyMap()

        val postIdValues = postIds.map { it.value }
        val mediaList = MediaTable.selectAll()
            .where { MediaTable.postId inList postIdValues }
            .orderBy(MediaTable.order to SortOrder.ASC)
            .map { row ->
                val postId = PostId(row[MediaTable.postId])
                postId to row.toMediaAttachment()
            }

        return mediaList.groupBy({ it.first }, { it.second })
    }

    /**
     * 将 ResultRow 映射为 PostDetail（不包含媒体，需要外部提供）
     */
    private fun ResultRow.toPostDetailWithMedia(mediaMap: Map<PostId, List<MediaAttachment>>): PostDetail {
        val postId = PostId(this[PostsTable.id])
        val media = mediaMap[postId] ?: emptyList()

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
            it[bookmarkCount] = stats.bookmarkCount
            it[viewCount] = stats.viewCount
        }

        if (updated == 0) {
            logger.debug("Post 不存在，无法更新统计: postId=${postId.value}")
            return@dbQuery PostError.PostNotFound(postId).left()
        }

        logger.info("Post 统计更新成功: postId=${postId.value}")
        Unit.right()
    }

    // ========== Like 相关实现 ==========

    override suspend fun likePost(userId: UserId, postId: PostId): Either<LikeError, PostStats> = dbQuery {
        try {
            logger.debug("用户点赞 Post: userId=${userId.value}, postId=${postId.value}")

            // 1. 检查 Post 是否存在
            val postExists = PostsTable.selectAll()
                .where { PostsTable.id eq postId.value }
                .count() > 0
            if (!postExists) {
                logger.debug("Post 不存在: postId=${postId.value}")
                return@dbQuery LikeError.PostNotFound(postId).left()
            }

            // 2. 检查是否已点赞
            val alreadyLiked = LikesTable.selectAll()
                .where { (LikesTable.userId eq userId.value) and (LikesTable.postId eq postId.value) }
                .count() > 0

            if (alreadyLiked) {
                logger.debug("用户已点赞: userId=${userId.value}, postId=${postId.value}")
                return@dbQuery LikeError.AlreadyLiked(userId, postId).left()
            }

            // 3. 插入 Like 记录
            LikesTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[LikesTable.userId] = userId.value
                it[LikesTable.postId] = postId.value
                it[createdAt] = System.currentTimeMillis()
            }

            // 4. 更新 PostsTable 的 likeCount
            PostsTable.update({ PostsTable.id eq postId.value }) {
                it[likeCount] = likeCount + 1
            }

            // 5. 返回更新后的统计信息
            val updatedRow = PostsTable.selectAll()
                .where { PostsTable.id eq postId.value }
                .single()
            val stats = updatedRow.toPostStats()
            logger.info("点赞成功: userId=${userId.value}, postId=${postId.value}")
            stats.right()

        } catch (e: Exception) {
            // 检查是否是UNIQUE约束违反（并发重复点赞）
            val isSqlException = e is java.sql.SQLException
            val sqlState = (e as? java.sql.SQLException)?.sqlState
            val isUniqueViolation = when {
                sqlState == "23505" -> true  // PostgreSQL UNIQUE_VIOLATION
                sqlState == "1062" -> true   // MySQL DUPLICATE_ENTRY
                else -> false
            }

            if (isSqlException && isUniqueViolation) {
                logger.debug("用户已点赞（并发检测）: userId=${userId.value}, postId=${postId.value}")
                return@dbQuery LikeError.AlreadyLiked(userId, postId).left()
            }

            // 检查异常消息中是否包含"duplicate"或"unique"关键词
            if (e.message?.contains("duplicate", ignoreCase = true) == true ||
                e.message?.contains("unique", ignoreCase = true) == true) {
                logger.debug("用户已点赞（异常消息检测）: userId=${userId.value}, postId=${postId.value}")
                return@dbQuery LikeError.AlreadyLiked(userId, postId).left()
            }

            logger.error("点赞失败: userId=${userId.value}, postId=${postId.value}", e)
            LikeError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    override suspend fun unlikePost(userId: UserId, postId: PostId): Either<LikeError, PostStats> = dbQuery {
        try {
            logger.debug("用户取消点赞 Post: userId=${userId.value}, postId=${postId.value}")

            // 1. 检查 Post 是否存在
            val postExists = PostsTable.selectAll()
                .where { PostsTable.id eq postId.value }
                .count() > 0
            if (!postExists) {
                logger.debug("Post 不存在: postId=${postId.value}")
                return@dbQuery LikeError.PostNotFound(postId).left()
            }

            // 2. 检查是否已点赞
            val isLiked = LikesTable.selectAll()
                .where { (LikesTable.userId eq userId.value) and (LikesTable.postId eq postId.value) }
                .count() > 0

            if (!isLiked) {
                logger.debug("用户未点赞: userId=${userId.value}, postId=${postId.value}")
                return@dbQuery LikeError.NotLiked(userId, postId).left()
            }

            // 3. 删除 Like 记录，并检查是否真的删除了
            val deletedCount = LikesTable.deleteWhere {
                (LikesTable.userId eq userId.value) and (LikesTable.postId eq postId.value)
            }

            if (deletedCount == 0) {
                logger.debug("用户未点赞（已被并发操作删除）: userId=${userId.value}, postId=${postId.value}")
                return@dbQuery LikeError.NotLiked(userId, postId).left()
            }

            // 4. 只有真的删除了，才减计数
            PostsTable.update({ PostsTable.id eq postId.value }) {
                it[likeCount] = likeCount - 1
            }

            // 5. 返回更新后的统计信息
            val updatedRow = PostsTable.selectAll()
                .where { PostsTable.id eq postId.value }
                .single()
            val stats = updatedRow.toPostStats()
            logger.info("取消点赞成功: userId=${userId.value}, postId=${postId.value}")
            stats.right()

        } catch (e: Exception) {
            logger.error("取消点赞失败: userId=${userId.value}, postId=${postId.value}", e)
            LikeError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    override suspend fun isLikedByUser(userId: UserId, postId: PostId): Either<LikeError, Boolean> = dbQuery {
        try {
            val liked = LikesTable.selectAll()
                .where { (LikesTable.userId eq userId.value) and (LikesTable.postId eq postId.value) }
                .count() > 0
            liked.right()
        } catch (e: Exception) {
            logger.error("检查点赞状态失败: userId=${userId.value}, postId=${postId.value}", e)
            LikeError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    override fun findUserLikes(userId: UserId, limit: Int, offset: Int): Flow<PostDetail> = flow {
        val details = dbQuery {
            logger.debug("查询用户点赞列表: userId=${userId.value}, limit=$limit, offset=$offset")

            // JOIN LikesTable + PostsTable + UsersTable - 多查询 1 条用于判断 hasMore
            val query = (LikesTable
                .innerJoin(PostsTable) { LikesTable.postId eq PostsTable.id }
                .innerJoin(UsersTable) { PostsTable.authorId eq UsersTable.id })
                .select(PostsTable.columns + UsersTable.columns)
                .where { LikesTable.userId eq userId.value }
                .orderBy(LikesTable.createdAt to SortOrder.DESC)
                .limit(limit + 1).offset(offset.toLong())

            val rows = query.toList()

            // 批量查询媒体附件
            val postIds = rows.map { PostId(it[PostsTable.id]) }
            val mediaMap = batchLoadMedia(postIds)

            rows.map { row -> row.toPostDetailWithMedia(mediaMap) }
        }

        details.forEach { detail ->
            emit(detail)
        }
    }

    // ========== Bookmark 相关实现 ==========

    override suspend fun bookmarkPost(userId: UserId, postId: PostId): Either<BookmarkError, Unit> = dbQuery {
        try {
            logger.debug("用户收藏 Post: userId=${userId.value}, postId=${postId.value}")

            // 1. 检查 Post 是否存在
            val postExists = PostsTable.selectAll()
                .where { PostsTable.id eq postId.value }
                .count() > 0
            if (!postExists) {
                logger.debug("Post 不存在: postId=${postId.value}")
                return@dbQuery BookmarkError.PostNotFound(postId).left()
            }

            // 2. 检查是否已收藏
            val alreadyBookmarked = BookmarksTable.selectAll()
                .where { (BookmarksTable.userId eq userId.value) and (BookmarksTable.postId eq postId.value) }
                .count() > 0

            if (alreadyBookmarked) {
                logger.debug("用户已收藏: userId=${userId.value}, postId=${postId.value}")
                return@dbQuery BookmarkError.AlreadyBookmarked(userId, postId).left()
            }

            // 3. 插入 Bookmark 记录
            BookmarksTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[BookmarksTable.userId] = userId.value
                it[BookmarksTable.postId] = postId.value
                it[createdAt] = System.currentTimeMillis()
            }

            // 4. 更新 PostsTable 的 bookmarkCount
            PostsTable.update({ PostsTable.id eq postId.value }) {
                it[bookmarkCount] = bookmarkCount + 1
            }

            logger.info("收藏成功: userId=${userId.value}, postId=${postId.value}")
            Unit.right()

        } catch (e: Exception) {
            // 检查是否是UNIQUE约束违反（并发重复收藏）
            val isSqlException = e is java.sql.SQLException
            val sqlState = (e as? java.sql.SQLException)?.sqlState
            val isUniqueViolation = when {
                sqlState == "23505" -> true  // PostgreSQL UNIQUE_VIOLATION
                sqlState == "1062" -> true   // MySQL DUPLICATE_ENTRY
                else -> false
            }

            if (isSqlException && isUniqueViolation) {
                logger.debug("用户已收藏（并发检测）: userId=${userId.value}, postId=${postId.value}")
                return@dbQuery BookmarkError.AlreadyBookmarked(userId, postId).left()
            }

            // 检查异常消息中是否包含"duplicate"或"unique"关键词
            if (e.message?.contains("duplicate", ignoreCase = true) == true ||
                e.message?.contains("unique", ignoreCase = true) == true) {
                logger.debug("用户已收藏（异常消息检测）: userId=${userId.value}, postId=${postId.value}")
                return@dbQuery BookmarkError.AlreadyBookmarked(userId, postId).left()
            }

            logger.error("收藏失败: userId=${userId.value}, postId=${postId.value}", e)
            BookmarkError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    override suspend fun unbookmarkPost(userId: UserId, postId: PostId): Either<BookmarkError, Unit> = dbQuery {
        try {
            logger.debug("用户取消收藏 Post: userId=${userId.value}, postId=${postId.value}")

            // 1. 检查 Post 是否存在
            val postExists = PostsTable.selectAll()
                .where { PostsTable.id eq postId.value }
                .count() > 0
            if (!postExists) {
                logger.debug("Post 不存在: postId=${postId.value}")
                return@dbQuery BookmarkError.PostNotFound(postId).left()
            }

            // 2. 检查是否已收藏
            val isBookmarked = BookmarksTable.selectAll()
                .where { (BookmarksTable.userId eq userId.value) and (BookmarksTable.postId eq postId.value) }
                .count() > 0

            if (!isBookmarked) {
                logger.debug("用户未收藏: userId=${userId.value}, postId=${postId.value}")
                return@dbQuery BookmarkError.NotBookmarked(userId, postId).left()
            }

            // 3. 删除 Bookmark 记录，并检查是否真的删除了
            val deletedCount = BookmarksTable.deleteWhere {
                (BookmarksTable.userId eq userId.value) and (BookmarksTable.postId eq postId.value)
            }

            if (deletedCount == 0) {
                logger.debug("用户未收藏（已被并发操作删除）: userId=${userId.value}, postId=${postId.value}")
                return@dbQuery BookmarkError.NotBookmarked(userId, postId).left()
            }

            // 4. 只有真的删除了，才减计数
            PostsTable.update({ PostsTable.id eq postId.value }) {
                it[bookmarkCount] = bookmarkCount - 1
            }

            logger.info("取消收藏成功: userId=${userId.value}, postId=${postId.value}")
            Unit.right()

        } catch (e: Exception) {
            logger.error("取消收藏失败: userId=${userId.value}, postId=${postId.value}", e)
            BookmarkError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    override suspend fun isBookmarkedByUser(userId: UserId, postId: PostId): Either<BookmarkError, Boolean> = dbQuery {
        try {
            val bookmarked = BookmarksTable.selectAll()
                .where { (BookmarksTable.userId eq userId.value) and (BookmarksTable.postId eq postId.value) }
                .count() > 0
            bookmarked.right()
        } catch (e: Exception) {
            logger.error("检查收藏状态失败: userId=${userId.value}, postId=${postId.value}", e)
            BookmarkError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    override fun findUserBookmarks(userId: UserId, limit: Int, offset: Int): Flow<PostDetail> = flow {
        val details = dbQuery {
            logger.debug("查询用户收藏列表: userId=${userId.value}, limit=$limit, offset=$offset")

            // JOIN BookmarksTable + PostsTable + UsersTable - 多查询 1 条用于判断 hasMore
            val query = (BookmarksTable
                .innerJoin(PostsTable) { BookmarksTable.postId eq PostsTable.id }
                .innerJoin(UsersTable) { PostsTable.authorId eq UsersTable.id })
                .select(PostsTable.columns + UsersTable.columns)
                .where { BookmarksTable.userId eq userId.value }
                .orderBy(BookmarksTable.createdAt to SortOrder.DESC)
                .limit(limit + 1).offset(offset.toLong())

            val rows = query.toList()

            // 批量查询媒体附件
            val postIds = rows.map { PostId(it[PostsTable.id]) }
            val mediaMap = batchLoadMedia(postIds)

            rows.map { row -> row.toPostDetailWithMedia(mediaMap) }
        }

        details.forEach { detail ->
            emit(detail)
        }
    }

    // ========== 批量查询交互状态（性能优化） ==========

    override suspend fun batchCheckLiked(userId: UserId, postIds: List<PostId>): Either<LikeError, Set<PostId>> = dbQuery {
        try {
            if (postIds.isEmpty()) {
                return@dbQuery emptySet<PostId>().right()
            }

            logger.debug("批量检查点赞状态: userId=${userId.value}, postCount=${postIds.size}")

            val postIdValues = postIds.map { it.value }
            val likedPostIds = LikesTable.selectAll()
                .where { (LikesTable.userId eq userId.value) and (LikesTable.postId inList postIdValues) }
                .map { row -> PostId(row[LikesTable.postId]) }
                .toSet()

            logger.debug("检查完成: liked=${likedPostIds.size}, total=${postIds.size}")
            likedPostIds.right()
        } catch (e: Exception) {
            logger.error("批量检查点赞状态失败: userId=${userId.value}, error=${e.message}", e)
            LikeError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    override suspend fun batchCheckBookmarked(userId: UserId, postIds: List<PostId>): Either<BookmarkError, Set<PostId>> = dbQuery {
        try {
            if (postIds.isEmpty()) {
                return@dbQuery emptySet<PostId>().right()
            }

            logger.debug("批量检查收藏状态: userId=${userId.value}, postCount=${postIds.size}")

            val postIdValues = postIds.map { it.value }
            val bookmarkedPostIds = BookmarksTable.selectAll()
                .where { (BookmarksTable.userId eq userId.value) and (BookmarksTable.postId inList postIdValues) }
                .map { row -> PostId(row[BookmarksTable.postId]) }
                .toSet()

            logger.debug("检查完成: bookmarked=${bookmarkedPostIds.size}, total=${postIds.size}")
            bookmarkedPostIds.right()
        } catch (e: Exception) {
            logger.error("批量检查收藏状态失败: userId=${userId.value}, error=${e.message}", e)
            BookmarkError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }
}
