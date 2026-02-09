package com.connor.domain.repository

import arrow.core.Either
import com.connor.domain.failure.AuthError
import com.connor.domain.failure.UserError
import com.connor.domain.model.*
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    /**
     * 保存用户
     * @return Either.Left(AuthError.UserAlreadyExists) 如果邮箱已存在
     */
    suspend fun save(user: User): Either<AuthError, User>

    /**
     * 根据邮箱查找用户
     * @return User 如果存在，否则 null
     */
    suspend fun findByEmail(email: Email): User?

    /**
     * 根据 UserId 查找用户
     * @return Either.Left(UserError.UserNotFound) 如果不存在
     */
    suspend fun findById(userId: UserId): Either<UserError, User>

    /**
     * 根据 Username 查找用户
     * @return Either.Left(UserError.UserNotFoundByUsername) 如果不存在
     */
    suspend fun findByUsername(username: Username): Either<UserError, User>

    /**
     * 更新用户资料（username、displayName、bio、avatarUrl）
     * @return Either.Left(UserError.UsernameAlreadyExists) 如果新 username 已被占用
     */
    suspend fun updateProfile(
        userId: UserId,
        username: Username? = null,
        displayName: DisplayName? = null,
        bio: Bio? = null,
        avatarUrl: String? = null
    ): Either<UserError, User>

    /**
     * 查询用户资料（包含统计信息）
     * @return Either.Left(UserError.UserNotFound) 如果不存在
     */
    suspend fun findProfile(userId: UserId): Either<UserError, UserProfile>

    /**
     * 查询用户资料（通过 username）
     * @return Either.Left(UserError.UserNotFoundByUsername) 如果不存在
     */
    suspend fun findProfileByUsername(username: Username): Either<UserError, UserProfile>

    // ========== Follow 相关方法 ==========

    /**
     * 关注用户
     * @return Either.Left(UserError.AlreadyFollowing) 如果已关注
     * @return Either.Left(UserError.FollowTargetNotFound) 如果目标用户不存在
     */
    suspend fun follow(followerId: UserId, followingId: UserId): Either<UserError, Follow>

    /**
     * 取消关注
     * @return Either.Left(UserError.NotFollowing) 如果未关注
     */
    suspend fun unfollow(followerId: UserId, followingId: UserId): Either<UserError, Unit>

    /**
     * 检查是否正在关注
     */
    suspend fun isFollowing(followerId: UserId, followingId: UserId): Boolean

    /**
     * 获取关注列表（我关注的人）
     * @return Flow<User> 流式返回，方便分页
     */
    fun findFollowing(userId: UserId, limit: Int = 20, offset: Int = 0): Flow<User>

    /**
     * 获取粉丝列表（关注我的人）
     * @return Flow<User> 流式返回，方便分页
     */
    fun findFollowers(userId: UserId, limit: Int = 20, offset: Int = 0): Flow<User>

    /**
     * 批量检查关注状态（避免 N+1 查询）
     * @return Set<UserId> 当前用户正在关注的用户ID集合
     */
    suspend fun batchCheckFollowing(followerId: UserId, userIds: List<UserId>): Set<UserId>
}