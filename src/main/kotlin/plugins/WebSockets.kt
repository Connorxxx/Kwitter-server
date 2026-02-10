package com.connor.plugins

import io.ktor.server.application.*
import io.ktor.server.websocket.*
import kotlin.time.Duration.Companion.seconds

/**
 * 配置 WebSocket 插件
 *
 * 配置项：
 * - pingPeriod: 心跳间隔（60秒）
 * - timeout: 连接超时（15秒）
 * - maxFrameSize: 最大帧大小（1MB，防止 DoS 攻击）
 * - masking: 是否启用掩码（false，服务器不需要）
 */
fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriod = 60.seconds
        timeout = 15.seconds
        maxFrameSize = 1024 * 1024  // 1MB
        masking = false
    }
}
