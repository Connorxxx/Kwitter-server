package com.connor.domain.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.AuthError

@JvmInline
value class DisplayName private constructor(val value: String) {
    companion object {
        private const val MIN_LENGTH = 1
        private const val MAX_LENGTH = 64

        operator fun invoke(value: String): Either<AuthError, DisplayName> {
            val trimmed = value.trim()
            return when {
                trimmed.isEmpty() ->
                    AuthError.InvalidDisplayName("昵称不能为空").left()
                trimmed.length > MAX_LENGTH ->
                    AuthError.InvalidDisplayName("昵称不能超过 $MAX_LENGTH 字符").left()
                else -> DisplayName(trimmed).right()
            }
        }

        // 从数据库加载时使用（已验证）
        fun unsafe(value: String): DisplayName = DisplayName(value)
    }
}
