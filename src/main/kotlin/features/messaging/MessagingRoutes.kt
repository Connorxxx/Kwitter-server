package com.connor.features.messaging

import com.connor.core.coroutine.ApplicationCoroutineScope
import com.connor.core.http.ApiErrorResponse
import com.connor.core.security.UserPrincipal
import com.connor.domain.model.ConversationId
import com.connor.domain.model.MessageId
import com.connor.domain.model.UserId
import com.connor.domain.usecase.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("MessagingRoutes")

fun Route.messagingRoutes(
    sendMessageUseCase: SendMessageUseCase,
    getConversationsUseCase: GetConversationsUseCase,
    getMessagesUseCase: GetMessagesUseCase,
    markConversationReadUseCase: MarkConversationReadUseCase,
    notifyNewMessageUseCase: NotifyNewMessageUseCase,
    deleteMessageUseCase: DeleteMessageUseCase,
    recallMessageUseCase: RecallMessageUseCase,
    appScope: ApplicationCoroutineScope
) {
    authenticate("auth-jwt") {
        route("/v1/conversations") {

            /**
             * GET /v1/conversations?limit=20&offset=0
             * List conversations for the current user
             */
            get {
                val principal = call.principal<UserPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@get
                }

                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

                val userId = UserId(principal.userId)
                val conversations = getConversationsUseCase(userId, limit, offset).toList()

                val hasMore = conversations.size > limit
                val items = if (hasMore) conversations.take(limit) else conversations

                call.respond(
                    HttpStatusCode.OK,
                    ConversationListResponse(
                        conversations = items.map { it.toResponse() },
                        hasMore = hasMore
                    )
                )
            }

            /**
             * POST /v1/conversations/messages
             * Send a message (auto-creates conversation if needed)
             */
            post("/messages") {
                val principal = call.principal<UserPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@post
                }

                val request = call.receive<SendMessageRequest>()
                val userId = UserId(principal.userId)

                val cmd = SendMessageCommand(
                    senderId = userId,
                    recipientId = UserId(request.recipientId),
                    content = request.content,
                    imageUrl = request.imageUrl,
                    replyToMessageId = request.replyToMessageId?.let(::MessageId)
                )

                val result = sendMessageUseCase(cmd)

                result.fold(
                    ifLeft = { error ->
                        val (status, body) = error.toHttpError()
                        call.respond(status, body)
                    },
                    ifRight = { sendResult ->
                        // Async notification (non-blocking)
                        appScope.launch {
                            try {
                                val recipientId = UserId(request.recipientId)
                                notifyNewMessageUseCase.execute(
                                    recipientId = recipientId,
                                    messageId = sendResult.message.id,
                                    conversationId = sendResult.conversation.id,
                                    senderDisplayName = principal.displayName,
                                    senderUsername = principal.username,
                                    contentPreview = sendResult.message.content.value.take(100)
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.error("Failed to notify new message", e)
                            }
                        }

                        call.respond(HttpStatusCode.Created, sendResult.message.toResponse())
                    }
                )
            }

            /**
             * GET /v1/conversations/{id}/messages?limit=50&offset=0
             * Get paginated message history for a conversation
             */
            get("/{id}/messages") {
                val principal = call.principal<UserPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@get
                }

                val conversationId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 conversationId 参数"))
                    return@get
                }

                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val beforeId = call.request.queryParameters["beforeId"]?.toLongOrNull()?.let { MessageId(it) }
                val userId = UserId(principal.userId)

                val result = getMessagesUseCase(ConversationId(conversationId.toLong()), userId, limit, offset, beforeId)

                result.fold(
                    ifLeft = { error ->
                        val (status, body) = error.toHttpError()
                        call.respond(status, body)
                    },
                    ifRight = { messagesFlow ->
                        val messages = messagesFlow.toList()
                        val hasMore = messages.size > limit
                        val items = if (hasMore) messages.take(limit) else messages
                        val messageResponses = items.map { it.toResponse() }
                        val nextCursor = if (hasMore) messageResponses.lastOrNull()?.id else null

                        call.respond(
                            HttpStatusCode.OK,
                            MessageListResponse(
                                messages = messageResponses,
                                hasMore = hasMore,
                                nextCursor = nextCursor
                            )
                        )
                    }
                )
            }

            /**
             * PUT /v1/conversations/{id}/read
             * Mark all messages in conversation as read
             */
            put("/{id}/read") {
                val principal = call.principal<UserPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@put
                }

                val conversationId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 conversationId 参数"))
                    return@put
                }

                val userId = UserId(principal.userId)
                val convId = ConversationId(conversationId.toLong())

                val result = markConversationReadUseCase(convId, userId)

                result.fold(
                    ifLeft = { error ->
                        val (status, body) = error.toHttpError()
                        call.respond(status, body)
                    },
                    ifRight = {
                        // Async notification: tell the other participant messages were read
                        appScope.launch {
                            try {
                                notifyNewMessageUseCase.notifyMessagesRead(convId, userId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.error("Failed to notify messages read", e)
                            }
                        }

                        call.respond(
                            HttpStatusCode.OK,
                            MarkReadResponse(
                                conversationId = convId.value,
                                readAt = System.currentTimeMillis()
                            )
                        )
                    }
                )
            }
        }

        route("/v1/messages") {
            /**
             * DELETE /v1/messages/{id}
             * Soft delete a message (only sender can delete)
             */
            delete("/{id}") {
                val principal = call.principal<UserPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@delete
                }

                val messageId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 messageId 参数"))
                    return@delete
                }

                val userId = UserId(principal.userId)
                val result = deleteMessageUseCase(MessageId(messageId.toLong()), userId)

                result.fold(
                    ifLeft = { error ->
                        val (status, body) = error.toHttpError()
                        call.respond(status, body)
                    },
                    ifRight = {
                        call.respond(HttpStatusCode.NoContent)
                    }
                )
            }

            /**
             * PUT /v1/messages/{id}/recall
             * Recall a message (within 3 minutes, only sender)
             */
            put("/{id}/recall") {
                val principal = call.principal<UserPrincipal>() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@put
                }

                val messageId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 messageId 参数"))
                    return@put
                }

                val userId = UserId(principal.userId)
                val msgId = MessageId(messageId.toLong())
                val result = recallMessageUseCase(msgId, userId)

                result.fold(
                    ifLeft = { error ->
                        val (status, body) = error.toHttpError()
                        call.respond(status, body)
                    },
                    ifRight = {
                        // Notify the other participant via WebSocket
                        appScope.launch {
                            try {
                                notifyNewMessageUseCase.notifyMessageRecalled(msgId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.error("Failed to notify message recalled", e)
                            }
                        }

                        call.respond(HttpStatusCode.OK, mapOf("messageId" to msgId.value, "recalled" to true))
                    }
                )
            }
        }
    }
}

