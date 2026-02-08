package com.connor.features.post

import com.connor.core.security.UserPrincipal
import com.connor.domain.failure.BookmarkError
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.usecase.BookmarkPostUseCase
import com.connor.domain.usecase.GetUserBookmarksUseCase
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
    getUserBookmarksUseCase: GetUserBookmarksUseCase
) {
    // 认证路由
    authenticate("auth-jwt") {
        // 收藏
        post("/v1/posts/{postId}/bookmark") {
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

            val result = bookmarkPostUseCase(UserId(principal.userId), PostId(postId))

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

            val result = unbookmarkPostUseCase(UserId(principal.userId), PostId(postId))

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

    // 公开路由（无需认证）
    get("/v1/users/{userId}/bookmarks") {
        val userId = call.parameters["userId"] ?: run {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(code = "MISSING_USER_ID", message = "缺少 userId 参数")
            )
            return@get
        }

        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

        val posts = getUserBookmarksUseCase(UserId(userId), limit, offset)
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
private fun BookmarkError.toHttpError(): Pair<HttpStatusCode, ErrorResponse> = when (this) {
    is BookmarkError.PostNotFound -> HttpStatusCode.NotFound to ErrorResponse(
        code = "POST_NOT_FOUND",
        message = "Post 不存在"
    )

    is BookmarkError.AlreadyBookmarked -> HttpStatusCode.Conflict to ErrorResponse(
        code = "ALREADY_BOOKMARKED",
        message = "已经收藏过这个 Post"
    )

    is BookmarkError.NotBookmarked -> HttpStatusCode.Conflict to ErrorResponse(
        code = "NOT_BOOKMARKED",
        message = "未曾收藏过这个 Post"
    )

    is BookmarkError.DatabaseError -> HttpStatusCode.InternalServerError to ErrorResponse(
        code = "DATABASE_ERROR",
        message = "服务器错误: $reason"
    )
}
