package com.connor.features.notification

import com.connor.core.security.UserPrincipal
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.infrastructure.sse.SseConnectionManager
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("NotificationCommandRoutes")

fun Route.notificationCommandRoutes(
    connectionManager: SseConnectionManager
) {
    authenticate("auth-jwt") {
        // POST /v1/notifications/posts/{postId}/subscribe
        post("/v1/notifications/posts/{postId}/subscribe") {
            val principal = call.principal<UserPrincipal>() ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }

            val postId = call.parameters["postId"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            val userId = UserId(principal.userId)
            connectionManager.subscribeToPost(userId, PostId(postId))
            logger.debug("User subscribed to post: userId={}, postId={}", userId.value, postId)

            call.respond(HttpStatusCode.OK)
        }

        // DELETE /v1/notifications/posts/{postId}/subscribe
        delete("/v1/notifications/posts/{postId}/subscribe") {
            val principal = call.principal<UserPrincipal>() ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return@delete
            }

            val postId = call.parameters["postId"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@delete
            }

            val userId = UserId(principal.userId)
            connectionManager.unsubscribeFromPost(userId, PostId(postId))
            logger.debug("User unsubscribed from post: userId={}, postId={}", userId.value, postId)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
