package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.raise.either
import com.connor.domain.failure.AuthError
import com.connor.domain.model.Bio
import com.connor.domain.model.DisplayName
import com.connor.domain.model.Email
import com.connor.domain.model.User
import com.connor.domain.model.UserId
import com.connor.domain.model.Username
import com.connor.domain.repository.UserRepository
import com.connor.domain.service.PasswordHasher
import org.slf4j.LoggerFactory
import java.util.UUID

class RegisterUseCase(
    private val userRepository: UserRepository,
    private val passwordHasher: PasswordHasher
) {
    private val logger = LoggerFactory.getLogger(RegisterUseCase::class.java)

    /**
     * 用户注册业务逻辑
     * Railway-Oriented Programming: 任何步骤失败都会短路返回错误
     */
    suspend operator fun invoke(cmd: RegisterCommand): Either<AuthError, User> {
        logger.info("开始注册流程: email=${cmd.email}, displayName=${cmd.displayName}")

        return either {
            // 1. 验证邮箱格式
            val email = Email(cmd.email).onLeft { error ->
                logger.warn("邮箱格式验证失败: email=${cmd.email}, error=$error")
            }.bind()
            logger.debug("邮箱格式验证通过: ${email.value}")

            // 2. 验证昵称格式
            val displayName = DisplayName(cmd.displayName).onLeft { error ->
                logger.warn("昵称格式验证失败: displayName=${cmd.displayName}, error=$error")
            }.bind()
            logger.debug("昵称格式验证通过: ${displayName.value}")

            // 3. 验证密码强度
            passwordHasher.validate(cmd.password).onLeft { error ->
                logger.warn("密码强度验证失败: email=${cmd.email}, error=$error")
            }.bind()
            logger.debug("密码强度验证通过")

            // 4. 生成默认 username（使用 userId 前 8 位，确保唯一）
            val userId = UserId(UUID.randomUUID().toString())
            val defaultUsername = "user_${userId.value.substring(0, 8)}"
            val username = Username.unsafe(defaultUsername)
            logger.debug("生成默认 username: ${username.value}")

            // 5. 创建用户实体
            val now = System.currentTimeMillis()
            val newUser = User(
                id = userId,
                email = email,
                passwordHash = passwordHasher.hash(cmd.password),
                username = username,
                displayName = displayName,
                bio = Bio.unsafe(""),  // 默认空简介
                avatarUrl = null,
                passwordChangedAt = now,
                createdAt = now
            )
            logger.debug("用户实体创建成功: userId=${userId.value}, username=${username.value}")

            // 6. 持久化（数据库会处理唯一性约束）
            val savedUser = userRepository.save(newUser).onLeft { error ->
                logger.error("用户保存失败: email=${cmd.email}, error=$error")
            }.bind()

            logger.info("注册成功: userId=${savedUser.id.value}, email=${savedUser.email.value}")
            savedUser
        }
    }
}

/**
 * 注册命令：解耦 HTTP Request 和 Domain 层
 */
data class RegisterCommand(
    val email: String,
    val password: String,
    val displayName: String
)
