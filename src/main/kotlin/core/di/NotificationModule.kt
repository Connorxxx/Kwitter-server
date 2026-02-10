package com.connor.core.di

import com.connor.core.coroutine.ApplicationCoroutineScope
import com.connor.domain.repository.NotificationRepository
import com.connor.domain.usecase.BroadcastPostCreatedUseCase
import com.connor.domain.usecase.BroadcastPostLikedUseCase
import com.connor.infrastructure.repository.InMemoryNotificationRepository
import com.connor.infrastructure.websocket.WebSocketConnectionManager
import org.koin.dsl.module

/**
 * 通知模块 DI 配置
 *
 * 包含：
 * - 应用级协程作用域
 * - WebSocket 连接管理器
 * - 通知 Repository 实现
 * - 通知相关 Use Cases
 */
val notificationModule = module {
    // Core: 应用级协程作用域（单例）
    single { ApplicationCoroutineScope() }

    // Infrastructure: WebSocket 连接管理
    single { WebSocketConnectionManager() }

    // Infrastructure: 通知 Repository 实现
    single<NotificationRepository> { InMemoryNotificationRepository(get()) }

    // Use Cases: 通知广播
    single { BroadcastPostCreatedUseCase(get()) }
    single { BroadcastPostLikedUseCase(get()) }
}
