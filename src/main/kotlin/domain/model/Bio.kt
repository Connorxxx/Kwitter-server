package com.connor.domain.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.UserError

/**
 * Bio - 用户简介
 *
 * 设计约束：
 * - 可选字段（允许空字符串）
 * - 最大 160 字符（类似 Twitter）
 */
@JvmInline
value class Bio private constructor(val value: String) {
    companion object {
        private const val MAX_LENGTH = 160

        operator fun invoke(value: String): Either<UserError, Bio> {
            val trimmed = value.trim()
            return when {
                trimmed.length > MAX_LENGTH ->
                    UserError.InvalidBio("简介不能超过 $MAX_LENGTH 字符").left()
                else -> Bio(trimmed).right()
            }
        }

        // 从数据库加载时使用（已验证）
        fun unsafe(value: String): Bio = Bio(value)
    }
}
