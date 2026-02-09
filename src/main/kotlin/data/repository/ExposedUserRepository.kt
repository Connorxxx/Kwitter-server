package com.connor.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.data.db.dbQuery
import com.connor.data.db.mapping.toDomain
import com.connor.data.db.schema.FollowsTable
import com.connor.data.db.schema.PostsTable
import com.connor.data.db.schema.UsersTable
import com.connor.domain.failure.AuthError
import com.connor.domain.failure.UserError
import com.connor.domain.model.*
import com.connor.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
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
                it[username] = user.username.value
                it[displayName] = user.displayName.value
                it[bio] = user.bio.value
                it[avatarUrl] = user.avatarUrl
                it[createdAt] = user.createdAt
            }

            logger.info("用户插入成功: userId=${user.id.value}, email=${user.email.value}")
            user.right()

        } catch (e: SQLException) {
            logger.error("数据库错误: sqlState=${e.sqlState}, message=${e.message}, email=${user.email.value}")

            when {
                e.sqlState == "23505" || e.sqlState == "23000" -> {
                    val constraintName = e.message?.lowercase() ?: ""
                    when {
                        constraintName.contains("email") -> {
                            logger.warn("邮箱已存在: email=${user.email.value}")
                            AuthError.UserAlreadyExists(user.email.value).left()
                        }
                        else -> {
                            logger.warn("未预期的唯一约束冲突: constraint=$constraintName", e)
                            throw e
                        }
                    }
                }
                else -> {
                    logger.error("未知数据库错误: sqlState=${e.sqlState}", e)
                    throw e
                }
            }
        }
    }

    override suspend fun findByEmail(email: Email): User? = dbQuery {
        UsersTable.selectAll()
            .where { UsersTable.email eq email.value }
            .singleOrNull()
            ?.toDomain()
    }

    override suspend fun findById(userId: UserId): Either<UserError, User> = dbQuery {
        val user = UsersTable.selectAll()
            .where { UsersTable.id eq userId.value }
            .singleOrNull()
            ?.toDomain()

        user?.right() ?: UserError.UserNotFound(userId).left()
    }

    override suspend fun findByUsername(username: Username): Either<UserError, User> = dbQuery {
        val user = UsersTable.selectAll()
            .where { UsersTable.username eq username.value }
            .singleOrNull()
            ?.toDomain()

        user?.right() ?: UserError.UserNotFoundByUsername(username).left()
    }

    override suspend fun updateProfile(
        userId: UserId,
        username: Username?,
        displayName: DisplayName?,
        bio: Bio?,
        avatarUrl: String?
    ): Either<UserError, User> = dbQuery {
        try {
            val updates = mutableMapOf<Column<*>, Any?>()

            username?.let { updates[UsersTable.username] = it.value }
            displayName?.let { updates[UsersTable.displayName] = it.value }
            bio?.let { updates[UsersTable.bio] = it.value }
            avatarUrl?.let { updates[UsersTable.avatarUrl] = it }

            if (updates.isEmpty()) {
                // 没有更新，直接返回当前用户
                return@dbQuery findById(userId)
            }

            val updated = UsersTable.update({ UsersTable.id eq userId.value }) { stmt ->
                updates.forEach { (column, value) ->
                    @Suppress("UNCHECKED_CAST")
                    stmt[column as Column<Any?>] = value
                }
            }

            if (updated == 0) {
                UserError.UserNotFound(userId).left()
            } else {
                findById(userId)
            }

        } catch (e: SQLException) {
            when {
                e.sqlState == "23505" || e.sqlState == "23000" -> {
                    val constraintName = e.message?.lowercase() ?: ""
                    when {
                        constraintName.contains("username") -> {
                            UserError.UsernameAlreadyExists(username?.value ?: "").left()
                        }
                        else -> throw e
                    }
                }
                else -> throw e
            }
        }
    }

    override suspend fun findProfile(userId: UserId): Either<UserError, UserProfile> = dbQuery {
        val user = findById(userId).getOrNull() ?: return@dbQuery UserError.UserNotFound(userId).left()

        val stats = calculateUserStats(userId)

        UserProfile(user, stats).right()
    }

    override suspend fun findProfileByUsername(username: Username): Either<UserError, UserProfile> = dbQuery {
        val user = findByUsername(username).getOrNull()
            ?: return@dbQuery UserError.UserNotFoundByUsername(username).left()

        val stats = calculateUserStats(user.id)

        UserProfile(user, stats).right()
    }

    // ========== Follow 相关实现 ==========

    override suspend fun follow(followerId: UserId, followingId: UserId): Either<UserError, Follow> = dbQuery {
        try {
            // 检查目标用户是否存在
            val targetExists = UsersTable.selectAll()
                .where { UsersTable.id eq followingId.value }
                .count() > 0

            if (!targetExists) {
                return@dbQuery UserError.FollowTargetNotFound(followingId).left()
            }

            val follow = Follow(followerId, followingId)

            FollowsTable.insert {
                it[FollowsTable.followerId] = followerId.value
                it[FollowsTable.followingId] = followingId.value
                it[createdAt] = follow.createdAt
            }

            follow.right()

        } catch (e: SQLException) {
            when {
                e.sqlState == "23505" || e.sqlState == "23000" -> {
                    UserError.AlreadyFollowing.left()
                }
                else -> throw e
            }
        }
    }

    override suspend fun unfollow(followerId: UserId, followingId: UserId): Either<UserError, Unit> = dbQuery {
        val deleted = FollowsTable.deleteWhere {
            (FollowsTable.followerId eq followerId.value) and (FollowsTable.followingId eq followingId.value)
        }

        if (deleted == 0) {
            UserError.NotFollowing.left()
        } else {
            Unit.right()
        }
    }

    override suspend fun isFollowing(followerId: UserId, followingId: UserId): Boolean = dbQuery {
        FollowsTable.selectAll()
            .where {
                (FollowsTable.followerId eq followerId.value) and (FollowsTable.followingId eq followingId.value)
            }
            .count() > 0
    }

    override fun findFollowing(userId: UserId, limit: Int, offset: Int): Flow<User> = flow {
        dbQuery {
            // JOIN follows and users: SELECT users.* FROM follows JOIN users ON follows.following_id = users.id
            val users = FollowsTable
                .join(UsersTable, JoinType.INNER, FollowsTable.followingId, UsersTable.id)
                .selectAll()
                .where { FollowsTable.followerId eq userId.value }
                .orderBy(FollowsTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toDomain() }

            users.forEach { emit(it) }
        }
    }

    override fun findFollowers(userId: UserId, limit: Int, offset: Int): Flow<User> = flow {
        dbQuery {
            // JOIN follows and users: SELECT users.* FROM follows JOIN users ON follows.follower_id = users.id
            val users = FollowsTable
                .join(UsersTable, JoinType.INNER, FollowsTable.followerId, UsersTable.id)
                .selectAll()
                .where { FollowsTable.followingId eq userId.value }
                .orderBy(FollowsTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toDomain() }

            users.forEach { emit(it) }
        }
    }

    override suspend fun batchCheckFollowing(followerId: UserId, userIds: List<UserId>): Set<UserId> = dbQuery {
        if (userIds.isEmpty()) return@dbQuery emptySet()

        FollowsTable.selectAll()
            .where {
                (FollowsTable.followerId eq followerId.value) and
                (FollowsTable.followingId inList userIds.map { it.value })
            }
            .map { UserId(it[FollowsTable.followingId]) }
            .toSet()
    }

    // ========== Helper Functions ==========

    private suspend fun calculateUserStats(userId: UserId): UserStats = dbQuery {
        // 统计 Following 数量
        val followingCount = FollowsTable.selectAll()
            .where { FollowsTable.followerId eq userId.value }
            .count()
            .toInt()

        // 统计 Followers 数量
        val followersCount = FollowsTable.selectAll()
            .where { FollowsTable.followingId eq userId.value }
            .count()
            .toInt()

        // 统计 Posts 数量（只统计顶层 Posts，不包括回复）
        val postsCount = PostsTable.selectAll()
            .where { (PostsTable.authorId eq userId.value) and PostsTable.parentId.isNull() }
            .count()
            .toInt()

        UserStats(
            userId = userId,
            followingCount = followingCount,
            followersCount = followersCount,
            postsCount = postsCount
        )
    }
}
