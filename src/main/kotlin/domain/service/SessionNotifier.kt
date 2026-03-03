package com.connor.domain.service

import com.connor.domain.model.UserId

/**
 * Session-level notification port (e.g. force logout via SSE).
 * Domain defines the contract; infrastructure provides SSE push.
 */
interface SessionNotifier {
    suspend fun notifySessionRevoked(userId: UserId, message: String)
}
