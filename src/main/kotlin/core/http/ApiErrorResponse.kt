package com.connor.core.http

import kotlinx.serialization.Serializable

/**
 * 全局 API 错误响应
 *
 * 用于 StatusPages 和其他全局插件的错误响应
 * 与 features 层解耦，避免反向依赖
 */
@Serializable
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
