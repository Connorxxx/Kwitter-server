package com.connor.features.notification

import kotlinx.serialization.Serializable

@Serializable
data class TypingRequest(val isTyping: Boolean)
