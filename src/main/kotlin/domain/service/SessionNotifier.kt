package com.connor.domain.service

import com.connor.domain.model.UserId

/**
 * Session-level notification port (e.g. force logout via WebSocket).
 * Domain defines the contract; infrastructure provides WebSocket push.
 */
interface SessionNotifier {
    suspend fun notifySessionRevoked(userId: UserId, message: String)
}
