package com.connor.infrastructure.service

import com.connor.domain.model.UserId
import com.connor.domain.service.PushNotificationService
import org.slf4j.LoggerFactory

class NoOpPushNotificationService : PushNotificationService {
    private val logger = LoggerFactory.getLogger(NoOpPushNotificationService::class.java)

    override suspend fun sendPush(userId: UserId, title: String, body: String, data: Map<String, String>) {
        logger.debug("Push notification (NoOp): userId={}, title={}", userId.value, title)
    }
}
