package com.connor.features.notification

import kotlinx.serialization.Serializable

/**
 * 客户端发送的 WebSocket 消息 DTO
 *
 * 定义客户端可以发送的各种控制消息格式
 */
@Serializable
data class WebSocketClientMessageDto(
    val type: String,
    val postId: Long? = null,
    val conversationId: Long? = null
)

/**
 * 服务端发送的 WebSocket 消息 DTO（通用包装器）
 *
 * 统一的消息格式，便于客户端解析
 */
@Serializable
data class WebSocketServerMessageDto(
    val type: String,
    val data: String? = null,
    val postId: Long? = null,
    val userId: Long? = null,
    val message: String? = null
)
