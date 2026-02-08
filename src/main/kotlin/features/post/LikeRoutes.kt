package com.connor.features.post

import com.connor.core.security.UserPrincipal
import com.connor.domain.failure.LikeError
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.usecase.GetUserLikesUseCase
import com.connor.domain.usecase.LikePostUseCase
import com.connor.domain.usecase.UnlikePostUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("LikeRoutes")

fun Route.likeRoutes(
    likePostUseCase: LikePostUseCase,
    unlikePostUseCase: UnlikePostUseCase,
    getUserLikesUseCase: GetUserLikesUseCase
) {
    // 认证路由
    authenticate("auth-jwt") {
        // 点赞
        post("/v1/posts/{postId}/like") {
            val principal = call.principal<UserPrincipal>() ?: run {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(code = "UNAUTHORIZED", message = "未授权访问")
                )
                return@post
            }

            val postId = call.parameters["postId"] ?: run {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = "MISSING_POST_ID", message = "缺少 postId 参数")
                )
                return@post
            }

            val result = likePostUseCase(UserId(principal.userId), PostId(postId))

            result.fold(
                ifLeft = { error ->
                    val (status, body) = error.toHttpError()
                    call.respond(status, body)
                },
                ifRight = { stats ->
                    logger.info("用户 ${principal.userId} 点赞 Post $postId")
                    call.respond(HttpStatusCode.OK, mapOf("stats" to stats.toDto()))
                }
            )
        }

        // 取消点赞
        delete("/v1/posts/{postId}/like") {
            val principal = call.principal<UserPrincipal>() ?: run {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(code = "UNAUTHORIZED", message = "未授权访问")
                )
                return@delete
            }

            val postId = call.parameters["postId"] ?: run {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = "MISSING_POST_ID", message = "缺少 postId 参数")
                )
                return@delete
            }

            val result = unlikePostUseCase(UserId(principal.userId), PostId(postId))

            result.fold(
                ifLeft = { error ->
                    val (status, body) = error.toHttpError()
                    call.respond(status, body)
                },
                ifRight = { stats ->
                    logger.info("用户 ${principal.userId} 取消点赞 Post $postId")
                    call.respond(HttpStatusCode.OK, mapOf("stats" to stats.toDto()))
                }
            )
        }
    }

    // 公开路由（无需认证）
    get("/v1/users/{userId}/likes") {
        val userId = call.parameters["userId"] ?: run {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(code = "MISSING_USER_ID", message = "缺少 userId 参数")
            )
            return@get
        }

        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

        val posts = getUserLikesUseCase(UserId(userId), limit, offset)
            .toList()
            .map { it.toResponse() }

        call.respond(
            HttpStatusCode.OK,
            PostListResponse(
                posts = posts,
                hasMore = posts.size == limit
            )
        )
    }
}

// 错误映射
private fun LikeError.toHttpError(): Pair<HttpStatusCode, ErrorResponse> = when (this) {
    is LikeError.PostNotFound -> HttpStatusCode.NotFound to ErrorResponse(
        code = "POST_NOT_FOUND",
        message = "Post 不存在"
    )

    is LikeError.AlreadyLiked -> HttpStatusCode.Conflict to ErrorResponse(
        code = "ALREADY_LIKED",
        message = "已经点赞过这个 Post"
    )

    is LikeError.NotLiked -> HttpStatusCode.Conflict to ErrorResponse(
        code = "NOT_LIKED",
        message = "未曾点赞过这个 Post"
    )

    is LikeError.DatabaseError -> HttpStatusCode.InternalServerError to ErrorResponse(
        code = "DATABASE_ERROR",
        message = "服务器错误: $reason"
    )
}
