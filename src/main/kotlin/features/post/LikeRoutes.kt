package com.connor.features.post

import arrow.core.Either
import com.connor.core.security.UserPrincipal
import com.connor.domain.failure.LikeError
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.usecase.GetUserLikesUseCase
import com.connor.domain.usecase.GetUserLikesWithStatusUseCase
import com.connor.domain.usecase.LikePostUseCase
import com.connor.domain.usecase.UnlikePostUseCase
import com.connor.plugins.authenticateOptional
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
    getUserLikesUseCase: GetUserLikesUseCase,
    getUserLikesWithStatusUseCase: GetUserLikesWithStatusUseCase
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

    // 公开路由（支持可选认证）
    authenticateOptional("auth-jwt") {
        get("/v1/users/{userId}/likes") {
            val startTime = System.currentTimeMillis()
            val userId = call.parameters["userId"] ?: run {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = "MISSING_USER_ID", message = "缺少 userId 参数")
                )
                return@get
            }

            val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val limit = rawLimit.coerceIn(1, 100)  // 下限 1，上限 100
            val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)  // 不允许负数

            try {
                logger.info("查询用户点赞列表: userId=$userId, limit=$limit, offset=$offset")

                // 获取当前用户ID（如果已认证）
                val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                // 调用 Use Case
                val likeItems = getUserLikesWithStatusUseCase(UserId(userId), limit, offset, currentUserId).toList()
                val duration = System.currentTimeMillis() - startTime

                // 检查是否有错误
                val failures = likeItems.filterIsInstance<Either.Left<*>>()
                if (failures.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    val error = (failures.first() as Either.Left<GetUserLikesWithStatusUseCase.UserLikesError>).value
                    logger.warn("用户点赞列表查询部分失败: count=${failures.size}, duration=${duration}ms")
                    val (status, message) = when (error) {
                        is GetUserLikesWithStatusUseCase.UserLikesError.LikesCheckFailed -> {
                            HttpStatusCode.InternalServerError to "Failed to check interaction state"
                        }
                        is GetUserLikesWithStatusUseCase.UserLikesError.BookmarksCheckFailed -> {
                            HttpStatusCode.InternalServerError to "Failed to check interaction state"
                        }
                    }
                    call.respond(status, ErrorResponse("USER_LIKES_STATE_ERROR", message))
                    return@get
                }

                // 提取成功的结果
                @Suppress("UNCHECKED_CAST")
                val successItems = likeItems.filterIsInstance<Either.Right<*>>()
                    .map { (it as Either.Right<GetUserLikesWithStatusUseCase.LikedPostItem>).value }

                logger.info("用户点赞列表查询成功: userId=$userId, count=${successItems.size}, duration=${duration}ms")

                // 映射为响应 DTO
                val postsResponse = successItems.map { item ->
                    item.postDetail.toResponse(
                        isLikedByCurrentUser = item.isLikedByCurrentUser,
                        isBookmarkedByCurrentUser = item.isBookmarkedByCurrentUser
                    )
                }

                call.respond(
                    HttpStatusCode.OK,
                    PostListResponse(
                        posts = postsResponse,
                        hasMore = postsResponse.size == limit
                    )
                )

            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                logger.error("用户点赞列表查询异常: userId=$userId, duration=${duration}ms, error=${e.message}", e)
                throw e
            }
        }
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
