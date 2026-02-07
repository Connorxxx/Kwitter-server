package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.raise.either
import com.connor.domain.failure.AuthError
import com.connor.domain.model.Email
import com.connor.domain.model.User
import com.connor.domain.repository.UserRepository
import com.connor.domain.service.PasswordHasher
import org.slf4j.LoggerFactory

class LoginUseCase(
    private val userRepository: UserRepository,
    private val passwordHasher: PasswordHasher
) {
    private val logger = LoggerFactory.getLogger(LoginUseCase::class.java)

    /**
     * 用户登录业务逻辑
     * Railway-Oriented Programming: 任何步骤失败都会短路返回错误
     */
    suspend operator fun invoke(cmd: LoginCommand): Either<AuthError, User> {
        logger.info("开始登录流程: email=${cmd.email}")

        return either {
            // 1. 验证邮箱格式
            val email = Email(cmd.email).onLeft { error ->
                logger.warn("邮箱格式验证失败: email=${cmd.email}, error=$error")
            }.bind()
            logger.debug("邮箱格式验证通过: ${email.value}")

            // 2. 查找用户
            val user = userRepository.findByEmail(email)
            if (user == null) {
                logger.warn("用户不存在: email=${cmd.email}")
                raise(AuthError.InvalidCredentials) // 模糊报错，不泄露用户是否存在
            }
            logger.debug("用户查询成功: userId=${user.id.value}")

            // 3. 验证密码
            val isPasswordValid = passwordHasher.verify(cmd.password, user.passwordHash)
            if (!isPasswordValid) {
                logger.warn("密码验证失败: email=${cmd.email}, userId=${user.id.value}")
                raise(AuthError.InvalidCredentials)
            }

            logger.info("登录成功: userId=${user.id.value}, email=${user.email.value}")
            user
        }
    }
}

/**
 * 登录命令：解耦 HTTP Request 和 Domain 层
 */
data class LoginCommand(
    val email: String,
    val password: String
)
