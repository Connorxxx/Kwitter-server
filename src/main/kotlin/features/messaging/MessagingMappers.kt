package com.connor.features.messaging

import com.connor.core.http.ApiErrorResponse
import com.connor.domain.failure.MessageError
import com.connor.domain.model.ConversationDetail
import com.connor.domain.model.Message
import io.ktor.http.*

fun ConversationDetail.toResponse(): ConversationResponse {
    return ConversationResponse(
        id = conversation.id.value,
        otherUser = ConversationUserDto(
            id = otherUser.id.value,
            displayName = otherUser.displayName.value,
            username = otherUser.username.value,
            avatarUrl = otherUser.avatarUrl
        ),
        lastMessage = lastMessage?.toResponse(),
        unreadCount = unreadCount,
        createdAt = conversation.createdAt
    )
}

fun Message.toResponse(): MessageResponse {
    return MessageResponse(
        id = id.value,
        conversationId = conversationId.value,
        senderId = senderId.value,
        content = when {
            isRecalled -> ""
            isDeleted -> ""
            else -> content.value
        },
        imageUrl = when {
            isRecalled || isDeleted -> null
            else -> imageUrl
        },
        replyToMessageId = replyToMessageId?.value,
        readAt = readAt,
        deletedAt = deletedAt,
        recalledAt = recalledAt,
        createdAt = createdAt
    )
}

fun MessageError.toHttpError(): Pair<HttpStatusCode, ApiErrorResponse> = when (this) {
    is MessageError.EmptyContent ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "EMPTY_CONTENT",
            message = "消息内容不能为空"
        )
    is MessageError.ContentTooLong ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "CONTENT_TOO_LONG",
            message = "消息内容过长：最多 $max 字符，当前 $actual 字符"
        )
    is MessageError.ConversationNotFound ->
        HttpStatusCode.NotFound to ApiErrorResponse(
            code = "CONVERSATION_NOT_FOUND",
            message = "对话不存在: ${conversationId.value}"
        )
    is MessageError.MessageNotFound ->
        HttpStatusCode.NotFound to ApiErrorResponse(
            code = "MESSAGE_NOT_FOUND",
            message = "消息不存在: ${messageId.value}"
        )
    is MessageError.NotConversationParticipant ->
        HttpStatusCode.Forbidden to ApiErrorResponse(
            code = "NOT_PARTICIPANT",
            message = "您不是该对话的参与者"
        )
    is MessageError.CannotMessageSelf ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "CANNOT_MESSAGE_SELF",
            message = "不能给自己发送消息"
        )
    is MessageError.RecipientNotFound ->
        HttpStatusCode.NotFound to ApiErrorResponse(
            code = "RECIPIENT_NOT_FOUND",
            message = "接收者不存在: ${recipientId.value}"
        )
    is MessageError.DmPermissionDenied ->
        HttpStatusCode.Forbidden to ApiErrorResponse(
            code = "DM_PERMISSION_DENIED",
            message = "对方仅允许互相关注的用户发送私信"
        )

    is MessageError.UserBlocked ->
        HttpStatusCode.Forbidden to ApiErrorResponse(
            code = "USER_BLOCKED",
            message = "无法发送消息，用户已被拉黑: ${userId.value}"
        )

    is MessageError.NotMessageSender ->
        HttpStatusCode.Forbidden to ApiErrorResponse(
            code = "NOT_MESSAGE_SENDER",
            message = "只有消息发送者可以执行此操作"
        )

    is MessageError.RecallTimeExpired ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "RECALL_TIME_EXPIRED",
            message = "消息撤回超时，仅支持发送后 3 分钟内撤回"
        )

    is MessageError.MessageAlreadyRecalled ->
        HttpStatusCode.Conflict to ApiErrorResponse(
            code = "MESSAGE_ALREADY_RECALLED",
            message = "消息已被撤回"
        )

    is MessageError.MessageAlreadyDeleted ->
        HttpStatusCode.Conflict to ApiErrorResponse(
            code = "MESSAGE_ALREADY_DELETED",
            message = "消息已被删除"
        )
}
