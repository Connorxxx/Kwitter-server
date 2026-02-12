package com.connor.infrastructure.websocket

import com.connor.domain.model.UserId
import com.connor.domain.service.SessionNotifier

class WebSocketSessionNotifier(
    private val connectionManager: WebSocketConnectionManager
) : SessionNotifier {
    override suspend fun notifySessionRevoked(userId: UserId, message: String) {
        connectionManager.sendToUser(userId, message)
    }
}
