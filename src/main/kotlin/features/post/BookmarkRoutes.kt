package com.connor.features.post

import arrow.core.Either
import com.connor.core.http.ApiErrorResponse
import com.connor.core.security.UserPrincipal
import com.connor.domain.failure.BookmarkError
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.usecase.BookmarkPostUseCase
import com.connor.domain.usecase.GetUserBookmarksUseCase
import com.connor.domain.usecase.GetUserBookmarksWithStatusUseCase
import com.connor.domain.usecase.UnbookmarkPostUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("BookmarkRoutes")

fun Route.bookmarkRoutes(
    bookmarkPostUseCase: BookmarkPostUseCase,
    unbookmarkPostUseCase: UnbookmarkPostUseCase,
    getUserBookmarksUseCase: GetUserBookmarksUseCase,
    getUserBookmarksWithStatusUseCase: GetUserBookmarksWithStatusUseCase
) {
    // 认证路由
    authenticate("auth-jwt") {
        // 收藏
        post("/v1/posts/{postId}/bookmark") {
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

            val result = bookmarkPostUseCase(UserId(principal.userId.toLong()), PostId(postId.toLong()))

            result.fold(
                ifLeft = { error ->
                    val (status, body) = error.toHttpError()
                    call.respond(status, body)
                },
                ifRight = {
                    logger.info("用户 ${principal.userId} 收藏 Post $postId")
                    call.respond(HttpStatusCode.OK, mapOf("message" to "收藏成功"))
                }
            )
        }

        // 取消收藏
        delete("/v1/posts/{postId}/bookmark") {
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

            val result = unbookmarkPostUseCase(UserId(principal.userId.toLong()), PostId(postId.toLong()))

            result.fold(
                ifLeft = { error ->
                    val (status, body) = error.toHttpError()
                    call.respond(status, body)
                },
                ifRight = {
                    logger.info("用户 ${principal.userId} 取消收藏 Post $postId")
                    call.respond(HttpStatusCode.OK, mapOf("message" to "取消收藏成功"))
                }
            )
        }
    }

    // 私有路由（需要认证）
    authenticate("auth-jwt") {
        get("/v1/users/{userId}/bookmarks") {
            val startTime = System.currentTimeMillis()
            val principal = call.principal<UserPrincipal>() ?: run {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiErrorResponse(code = "UNAUTHORIZED", message = "未授权访问")
                )
                return@get
            }

            val userId = call.parameters["userId"] ?: run {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorResponse(code = "MISSING_USER_ID", message = "缺少 userId 参数")
                )
                return@get
            }

            // 权限检查：只能查看自己的收藏
            if (principal.userId != userId) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ApiErrorResponse(code = "FORBIDDEN", message = "无权访问其他用户的收藏列表")
                )
                return@get
            }

            val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val limit = rawLimit.coerceIn(1, 100)  // 下限 1，上限 100
            val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)  // 不允许负数

            try {
                logger.info("查询用户收藏列表: userId=$userId, limit=$limit, offset=$offset")

                // 使用当前认证用户的 ID
                val currentUserId = UserId(principal.userId.toLong())

                // 调用 Use Case
                val bookmarkItems = getUserBookmarksWithStatusUseCase(UserId(userId.toLong()), limit, offset, currentUserId).toList()
                val duration = System.currentTimeMillis() - startTime

                // 检查是否有错误
                val failures = bookmarkItems.filterIsInstance<Either.Left<*>>()
                if (failures.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    val error = (failures.first() as Either.Left<GetUserBookmarksWithStatusUseCase.UserBookmarksError>).value
                    logger.warn("用户收藏列表查询部分失败: count=${failures.size}, duration=${duration}ms")
                    val (status, message) = when (error) {
                        is GetUserBookmarksWithStatusUseCase.UserBookmarksError.UserNotFound -> {
                            HttpStatusCode.NotFound to "用户不存在"
                        }
                        is GetUserBookmarksWithStatusUseCase.UserBookmarksError.LikesCheckFailed -> {
                            HttpStatusCode.InternalServerError to "Failed to check interaction state"
                        }
                        is GetUserBookmarksWithStatusUseCase.UserBookmarksError.BookmarksCheckFailed -> {
                            HttpStatusCode.InternalServerError to "Failed to check interaction state"
                        }
                    }
                    call.respond(status, ApiErrorResponse("USER_BOOKMARKS_ERROR", message))
                    return@get
                }

                // 提取成功的结果
                @Suppress("UNCHECKED_CAST")
                val successItems = bookmarkItems.filterIsInstance<Either.Right<*>>()
                    .map { (it as Either.Right<GetUserBookmarksWithStatusUseCase.BookmarkedPostItem>).value }

                logger.info("用户收藏列表查询成功: userId=$userId, count=${successItems.size}, duration=${duration}ms")

                // 计算 hasMore 并裁剪结果
                val hasMore = successItems.size > limit
                val itemsToReturn = if (hasMore) successItems.take(limit) else successItems

                // 映射为响应 DTO
                val postsResponse = itemsToReturn.map { item ->
                    item.postDetail.toResponse(
                        isLikedByCurrentUser = item.isLikedByCurrentUser,
                        isBookmarkedByCurrentUser = item.isBookmarkedByCurrentUser
                    )
                }

                call.respond(
                    HttpStatusCode.OK,
                    PostListResponse(
                        posts = postsResponse,
                        hasMore = hasMore
                    )
                )

            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                logger.error("用户收藏列表查询异常: userId=$userId, duration=${duration}ms, error=${e.message}", e)
                throw e
            }
        }
    }
}

// 错误映射
private fun BookmarkError.toHttpError(): Pair<HttpStatusCode, ApiErrorResponse> = when (this) {
    is BookmarkError.PostNotFound -> HttpStatusCode.NotFound to ApiErrorResponse(
        code = "POST_NOT_FOUND",
        message = "Post 不存在"
    )

    is BookmarkError.AlreadyBookmarked -> HttpStatusCode.Conflict to ApiErrorResponse(
        code = "ALREADY_BOOKMARKED",
        message = "已经收藏过这个 Post"
    )

    is BookmarkError.NotBookmarked -> HttpStatusCode.Conflict to ApiErrorResponse(
        code = "NOT_BOOKMARKED",
        message = "未曾收藏过这个 Post"
    )

    is BookmarkError.DatabaseError -> HttpStatusCode.InternalServerError to ApiErrorResponse(
        code = "DATABASE_ERROR",
        message = "服务器错误: $reason"
    )
}
