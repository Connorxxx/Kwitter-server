package com.connor.core.di

import com.connor.core.coroutine.ApplicationCoroutineScope
import com.connor.domain.repository.NotificationRepository
import com.connor.domain.usecase.BroadcastPostCreatedUseCase
import com.connor.domain.usecase.BroadcastPostLikedUseCase
import com.connor.domain.usecase.BroadcastPostUnlikedUseCase
import com.connor.infrastructure.repository.InMemoryNotificationRepository
import com.connor.infrastructure.sse.SseConnectionManager
import org.koin.dsl.module

val notificationModule = module {
    // Core: 应用级协程作用域（单例）
    single { ApplicationCoroutineScope() }

    // Infrastructure: SSE 连接管理
    single { SseConnectionManager() }

    // Infrastructure: 通知 Repository 实现
    single<NotificationRepository> { InMemoryNotificationRepository(get()) }

    // Use Cases: 通知广播
    single { BroadcastPostCreatedUseCase(get()) }
    single { BroadcastPostLikedUseCase(get()) }
    single { BroadcastPostUnlikedUseCase(get()) }
}
