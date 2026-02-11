package com.connor.domain.service

import com.connor.domain.model.UserId

interface PushNotificationService {
    suspend fun sendPush(userId: UserId, title: String, body: String, data: Map<String, String> = emptyMap())
}
