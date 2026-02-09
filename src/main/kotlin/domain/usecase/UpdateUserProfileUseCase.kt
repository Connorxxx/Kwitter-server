package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.UserError
import com.connor.domain.model.*
import com.connor.domain.repository.UserRepository
import org.slf4j.LoggerFactory

/**
 * 更新用户资料命令
 */
data class UpdateProfileCommand(
    val userId: UserId,
    val username: String? = null,
    val displayName: String? = null,
    val bio: String? = null,
    val avatarUrl: String? = null
)

/**
 * 更新用户资料 UseCase
 *
 * 职责：
 * - 验证输入（username、displayName、bio）
 * - 调用 Repository 更新
 * - 返回更新后的用户信息
 *
 * 设计原理：
 * - UseCase 层验证业务规则（Value Object 构造）
 * - Repository 层处理数据持久化和唯一性约束
 */
class UpdateUserProfileUseCase(
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(UpdateUserProfileUseCase::class.java)

    suspend operator fun invoke(command: UpdateProfileCommand): Either<UserError, User> {
        logger.info("更新用户资料: userId=${command.userId.value}, username=${command.username}")

        // 验证并构造 Value Objects
        val usernameResult = command.username?.let { Username(it) }
        val displayNameResult = command.displayName?.let { DisplayName(it) }
        val bioResult = command.bio?.let { Bio(it) }

        // 检查验证错误（fail-fast）
        usernameResult?.fold(
            ifLeft = { return it.left() },
            ifRight = { /* 验证成功 */ }
        )

        displayNameResult?.fold(
            ifLeft = { error ->
                // DisplayName 验证失败时，将 AuthError 转换为 UserError
                return when (error) {
                    is com.connor.domain.failure.AuthError.InvalidDisplayName ->
                        UserError.InvalidUsername(error.reason).left()
                    else -> UserError.InvalidUsername("Invalid display name").left()
                }
            },
            ifRight = { /* 验证成功 */ }
        )

        bioResult?.fold(
            ifLeft = { return it.left() },
            ifRight = { /* 验证成功 */ }
        )

        // 调用 Repository 更新
        return userRepository.updateProfile(
            userId = command.userId,
            username = usernameResult?.getOrNull(),
            displayName = displayNameResult?.getOrNull(),
            bio = bioResult?.getOrNull(),
            avatarUrl = command.avatarUrl
        )
    }
}
