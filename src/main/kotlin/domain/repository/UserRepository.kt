package com.connor.domain.repository

import arrow.core.Either
import com.connor.domain.failure.AuthError
import com.connor.domain.model.Email
import com.connor.domain.model.User

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
}