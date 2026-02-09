package com.connor.domain.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.UserError

/**
 * Username - 用于 @ 和显示的唯一标识符
 *
 * 设计约束：
 * - 必须唯一（数据库级别 unique index）
 * - 只允许字母、数字、下划线
 * - 3-20 字符
 * - 不区分大小写（存储时统一小写）
 */
@JvmInline
value class Username private constructor(val value: String) {
    companion object {
        private const val MIN_LENGTH = 3
        private const val MAX_LENGTH = 20
        private val VALID_PATTERN = "^[a-z0-9_]+$".toRegex()

        operator fun invoke(value: String): Either<UserError, Username> {
            val normalized = value.trim().lowercase()  // 规范化：统一小写
            return when {
                normalized.length < MIN_LENGTH ->
                    UserError.InvalidUsername("用户名至少需要 $MIN_LENGTH 字符").left()
                normalized.length > MAX_LENGTH ->
                    UserError.InvalidUsername("用户名不能超过 $MAX_LENGTH 字符").left()
                !normalized.matches(VALID_PATTERN) ->
                    UserError.InvalidUsername("用户名只能包含字母、数字和下划线").left()
                else -> Username(normalized).right()
            }
        }

        // 从数据库加载时使用（已验证）
        fun unsafe(value: String): Username = Username(value)
    }
}
