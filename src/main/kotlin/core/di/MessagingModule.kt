package com.connor.core.di

import com.connor.data.repository.ExposedMessageRepository
import com.connor.domain.repository.MessageRepository
import com.connor.domain.service.PushNotificationService
import com.connor.domain.usecase.*
import com.connor.infrastructure.service.NoOpPushNotificationService
import org.koin.dsl.module

val messagingModule = module {
    // Data: Message Repository
    single<MessageRepository> { ExposedMessageRepository() }

    // Infrastructure: Push Notification Service (NoOp, swap to FCM later)
    single<PushNotificationService> { NoOpPushNotificationService() }

    // Use Cases
    single { SendMessageUseCase(get(), get()) }
    single { GetConversationsUseCase(get()) }
    single { GetMessagesUseCase(get()) }
    single { MarkConversationReadUseCase(get()) }
    single { NotifyNewMessageUseCase(get(), get(), get()) }
    single { DeleteMessageUseCase(get()) }
    single { RecallMessageUseCase(get()) }
}
