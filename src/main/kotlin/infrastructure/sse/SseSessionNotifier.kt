package com.connor.infrastructure.sse

import com.connor.domain.model.UserId
import com.connor.domain.service.SessionNotifier
import io.ktor.sse.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.slf4j.LoggerFactory

class SseSessionNotifier(
    private val connectionManager: SseConnectionManager
) : SessionNotifier {
    private val logger = LoggerFactory.getLogger(SseSessionNotifier::class.java)

    override suspend fun notifySessionRevoked(userId: UserId, message: String) {
        connectionManager.sendToUser(userId, "auth_revoked", message)
        logger.info("Sent auth_revoked to userId={}", userId.value)
    }
}
