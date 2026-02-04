package com.connor.domain.repository

import arrow.core.Either
import com.connor.domain.failure.AuthError
import com.connor.domain.model.Email
import com.connor.domain.model.User

interface UserRepository {
    // 注册：保存用户，如果邮箱冲突则失败
    suspend fun save(user: User): Either<AuthError,User>

    // 登录：根据邮箱查找
    suspend fun findByEmail(email: Email): User?

    // 辅助：检查邮箱是否存在
    suspend fun existsByEmail(email: Email): Boolean
}