package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.left
import com.connor.domain.failure.AuthError
import com.connor.domain.model.Email
import com.connor.domain.model.User
import com.connor.domain.model.UserId
import com.connor.domain.repository.UserRepository
import com.connor.domain.service.PasswordHasher
import java.util.UUID

class RegisterUseCase(
    private val userRepository: UserRepository,
    private val passwordHasher: PasswordHasher
) {
    // 输入参数通常是一个简单的 Data Class 或者 DTO
    suspend operator fun invoke(cmd: RegisterCommand): Either<AuthError, User> {
        val email = Email(cmd.email)

        // 1. 校验规则 (这里只是简单示例，实际可能更复杂)
        if (userRepository.existsByEmail(email)) {
            return AuthError.UserAlreadyExists(cmd.email).left()
        }

        // 2. 业务逻辑：生成 ID，Hash 密码
        val newUser = User(
            id = UserId(UUID.randomUUID().toString()), // 这里可以暂时用 UUID.randomUUID().toString() 占位
            email = email,
            passwordHash = passwordHasher.hash(cmd.password), // 核心：加密是业务规则
            displayName = cmd.displayName
        )

        // 3. 持久化
        return userRepository.save(newUser)
    }
}

// 用简单的 Command 对象传输参数，解耦 HTTP Request
data class RegisterCommand(
    val email: String,
    val password: String,
    val displayName: String
)