package com.connor.features.post

import com.connor.core.coroutine.ApplicationCoroutineScope
import com.connor.core.http.ApiErrorResponse
import com.connor.core.security.UserPrincipal
import com.connor.domain.failure.LikeError
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.usecase.BroadcastPostLikedUseCase
import com.connor.domain.usecase.LikePostUseCase
import com.connor.domain.usecase.UnlikePostUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("LikeRoutes")

fun Route.likeRoutes(
    likePostUseCase: LikePostUseCase,
    unlikePostUseCase: UnlikePostUseCase,
    broadcastPostLikedUseCase: BroadcastPostLikedUseCase,
    appScope: ApplicationCoroutineScope
) {
    // 认证路由
    authenticate("auth-jwt") {
        // 点赞
        post("/v1/posts/{postId}/like") {
            val principal = call.principal<UserPrincipal>() ?: run {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiErrorResponse(code = "UNAUTHORIZED", message = "未授权访问")
                )
                return@post
            }

            val postId = call.parameters["postId"] ?: run {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorResponse(code = "MISSING_POST_ID", message = "缺少 postId 参数")
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

                    // 异步触发实时通知（使用应用级协程作用域）
                    appScope.launch {
                        try {
                            broadcastPostLikedUseCase.execute(
                                postId = PostId(postId),
                                likedByUserId = UserId(principal.userId),
                                likedByDisplayName = principal.displayName,
                                likedByUsername = principal.username,
                                newLikeCount = stats.likeCount
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 通知失败不影响响应
                            logger.error("Failed to broadcast post liked", e)
                        }
                    }

                    call.respond(HttpStatusCode.OK, mapOf("stats" to stats.toDto()))
                }
            )
        }

        // 取消点赞
        delete("/v1/posts/{postId}/like") {
            val principal = call.principal<UserPrincipal>() ?: run {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiErrorResponse(code = "UNAUTHORIZED", message = "未授权访问")
                )
                return@delete
            }

            val postId = call.parameters["postId"] ?: run {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorResponse(code = "MISSING_POST_ID", message = "缺少 postId 参数")
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
}

// 错误映射
private fun LikeError.toHttpError(): Pair<HttpStatusCode, ApiErrorResponse> = when (this) {
    is LikeError.PostNotFound -> HttpStatusCode.NotFound to ApiErrorResponse(
        code = "POST_NOT_FOUND",
        message = "Post 不存在"
    )

    is LikeError.AlreadyLiked -> HttpStatusCode.Conflict to ApiErrorResponse(
        code = "ALREADY_LIKED",
        message = "已经点赞过这个 Post"
    )

    is LikeError.NotLiked -> HttpStatusCode.Conflict to ApiErrorResponse(
        code = "NOT_LIKED",
        message = "未曾点赞过这个 Post"
    )

    is LikeError.DatabaseError -> HttpStatusCode.InternalServerError to ApiErrorResponse(
        code = "DATABASE_ERROR",
        message = "服务器错误: $reason"
    )
}
