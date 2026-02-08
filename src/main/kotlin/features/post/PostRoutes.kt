package com.connor.features.post

import arrow.core.Either
import com.connor.core.security.UserPrincipal
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.usecase.*
import com.connor.plugins.authenticateOptional
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PostRoutes")

fun Route.postRoutes(
    createPostUseCase: CreatePostUseCase,
    getPostUseCase: GetPostUseCase,
    getTimelineWithStatusUseCase: GetTimelineWithStatusUseCase,
    getRepliesUseCase: GetRepliesUseCase,
    getRepliesWithStatusUseCase: GetRepliesWithStatusUseCase,
    getUserPostsUseCase: GetUserPostsUseCase,
    getUserPostsWithStatusUseCase: GetUserPostsWithStatusUseCase,
    getPostDetailWithStatusUseCase: GetPostDetailWithStatusUseCase
) {
    route("/v1/posts") {
        // ========== 公开路由（可选认证）==========
        // 这些路由使用 authenticateOptional 包裹，支持认证用户和未认证用户
        // 认证用户可以在 call.principal<UserPrincipal>() 中获取用户信息
        // 未认证用户 call.principal<UserPrincipal>() 返回 null
        authenticateOptional("auth-jwt") {

            /**
             * GET /v1/posts/timeline?limit=20&offset=0
             * 获取时间线（全站最新 Posts）
             * 如果用户已认证，返回当前用户的交互状态（点赞/收藏）
             */
            get("/timeline") {
            val startTime = System.currentTimeMillis()
            val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val limit = rawLimit.coerceIn(1, 100)  // 下限 1，上限 100
            val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)  // 不允许负数

            try {
                logger.info("查询时间线: limit=$limit, offset=$offset")

                // 获取当前用户ID（如果已认证）
                val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                // 调用 Use Case（业务编排在 UseCase 层，Route 只做协议转换）
                val timelineItems = getTimelineWithStatusUseCase(limit, offset, currentUserId).toList()
                val duration = System.currentTimeMillis() - startTime

                // 检查是否有错误
                val failures = timelineItems.filterIsInstance<Either.Left<*>>()
                if (failures.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    val error = (failures.first() as Either.Left<GetTimelineWithStatusUseCase.TimelineError>).value
                    logger.warn("时间线查询部分失败: count=${failures.size}, duration=${duration}ms")
                    val (status, message) = when (error) {
                        is GetTimelineWithStatusUseCase.TimelineError.LikesCheckFailed -> {
                            HttpStatusCode.InternalServerError to "Failed to check interaction state"
                        }
                        is GetTimelineWithStatusUseCase.TimelineError.BookmarksCheckFailed -> {
                            HttpStatusCode.InternalServerError to "Failed to check interaction state"
                        }
                    }
                    call.respond(status, ErrorResponse("TIMELINE_STATE_ERROR", message))
                    return@get
                }

                // 提取成功的结果
                @Suppress("UNCHECKED_CAST")
                val successItems = timelineItems.filterIsInstance<Either.Right<*>>()
                    .map { (it as Either.Right<GetTimelineWithStatusUseCase.TimelineItem>).value }

                logger.info("时间线查询成功: count=${successItems.size}, duration=${duration}ms")

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
                    logger.error("时间线查询异常: duration=${duration}ms, error=${e.message}", e)
                    throw e
                }
            }

            /**
             * GET /v1/posts/{postId}
             * 获取 Post 详情及当前用户的交互状态
             */
            get("/{postId}") {
                val startTime = System.currentTimeMillis()
                val postId = call.parameters["postId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_PARAM", "缺少 postId 参数"))
                    return@get
                }

                try {
                    logger.info("查询 Post 详情: postId=$postId")

                    // 获取当前用户ID（如果已认证）- authenticateOptional 保证这可以工作
                    val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                    // 调用 UseCase（包含详情+交互状态的完整查询，遵循Hex架构）
                    val result = getPostDetailWithStatusUseCase(
                        postId = PostId(postId),
                        currentUserId = currentUserId
                    )
                    val duration = System.currentTimeMillis() - startTime

                    result.fold(
                        ifLeft = { error ->
                            val (status, body) = error.toHttpError()
                            logger.warn("Post 查询失败: postId=$postId, error=${error.javaClass.simpleName}, duration=${duration}ms")
                            call.respond(status, body)
                        },
                        ifRight = { detailWithStatus ->
                            logger.info("Post 查询成功: postId=$postId, duration=${duration}ms")
                            call.respond(
                                HttpStatusCode.OK,
                                detailWithStatus.postDetail.toResponse(
                                    isLikedByCurrentUser = detailWithStatus.isLikedByCurrentUser,
                                    isBookmarkedByCurrentUser = detailWithStatus.isBookmarkedByCurrentUser
                                )
                            )
                        }
                    )

                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - startTime
                    logger.error("Post 查询异常: postId=$postId, duration=${duration}ms, error=${e.message}", e)
                    throw e
                }
            }

            /**
             * GET /v1/posts/{postId}/replies?limit=20&offset=0
             * 获取 Post 的回复列表
             * 如果用户已认证，返回当前用户的交互状态（点赞/收藏）
             */
            get("/{postId}/replies") {
                val startTime = System.currentTimeMillis()
                val postId = call.parameters["postId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_PARAM", "缺少 postId 参数"))
                    return@get
                }
                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val limit = rawLimit.coerceIn(1, 100)  // 下限 1，上限 100
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

                try {
                    logger.info("查询回复列表: postId=$postId, limit=$limit, offset=$offset")

                    // 获取当前用户ID（如果已认证）
                    val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                    // 调用 Use Case（业务编排在 UseCase 层，Route 只做协议转换）
                    val replyItems = getRepliesWithStatusUseCase(PostId(postId), limit, offset, currentUserId).toList()
                    val duration = System.currentTimeMillis() - startTime

                    // 检查是否有错误
                    val failures = replyItems.filterIsInstance<Either.Left<*>>()
                    if (failures.isNotEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        val error = (failures.first() as Either.Left<GetRepliesWithStatusUseCase.ReplyError>).value
                        logger.warn("回复查询部分失败: count=${failures.size}, duration=${duration}ms")
                        val (status, message) = when (error) {
                            is GetRepliesWithStatusUseCase.ReplyError.LikesCheckFailed -> {
                                HttpStatusCode.InternalServerError to "Failed to check interaction state"
                            }
                            is GetRepliesWithStatusUseCase.ReplyError.BookmarksCheckFailed -> {
                                HttpStatusCode.InternalServerError to "Failed to check interaction state"
                            }
                        }
                        call.respond(status, ErrorResponse("REPLY_STATE_ERROR", message))
                        return@get
                    }

                    // 提取成功的结果
                    @Suppress("UNCHECKED_CAST")
                    val successItems = replyItems.filterIsInstance<Either.Right<*>>()
                        .map { (it as Either.Right<GetRepliesWithStatusUseCase.ReplyItem>).value }

                    logger.info("回复查询成功: postId=$postId, count=${successItems.size}, duration=${duration}ms")

                    // 映射为响应 DTO
                    val repliesResponse = successItems.map { item ->
                        item.postDetail.toResponse(
                            isLikedByCurrentUser = item.isLikedByCurrentUser,
                            isBookmarkedByCurrentUser = item.isBookmarkedByCurrentUser
                        )
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        PostListResponse(
                            posts = repliesResponse,
                            hasMore = repliesResponse.size == limit
                        )
                    )

                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - startTime
                    logger.error("回复查询异常: postId=$postId, duration=${duration}ms, error=${e.message}", e)
                    throw e
                }
            }

            /**
             * GET /v1/posts/users/{userId}?limit=20&offset=0
             * 获取用户的 Posts（不包括回复）
             * 如果用户已认证，返回当前用户的交互状态（点赞/收藏）
             */
            get("/users/{userId}") {
                val startTime = System.currentTimeMillis()
                val userId = call.parameters["userId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_PARAM", "缺少 userId 参数"))
                    return@get
                }
                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val limit = rawLimit.coerceIn(1, 100)  // 下限 1，上限 100
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

                try {
                    logger.info("查询用户 Posts: userId=$userId, limit=$limit, offset=$offset")

                    // 获取当前用户ID（如果已认证）
                    val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                    // 调用 Use Case（业务编排在 UseCase 层，Route 只做协议转换）
                    val postItems = getUserPostsWithStatusUseCase(UserId(userId), limit, offset, currentUserId).toList()
                    val duration = System.currentTimeMillis() - startTime

                    // 检查是否有错误
                    val failures = postItems.filterIsInstance<Either.Left<*>>()
                    if (failures.isNotEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        val error = (failures.first() as Either.Left<GetUserPostsWithStatusUseCase.UserPostError>).value
                        logger.warn("用户Posts查询部分失败: count=${failures.size}, duration=${duration}ms")
                        val (status, message) = when (error) {
                            is GetUserPostsWithStatusUseCase.UserPostError.LikesCheckFailed -> {
                                HttpStatusCode.InternalServerError to "Failed to check interaction state"
                            }
                            is GetUserPostsWithStatusUseCase.UserPostError.BookmarksCheckFailed -> {
                                HttpStatusCode.InternalServerError to "Failed to check interaction state"
                            }
                        }
                        call.respond(status, ErrorResponse("USER_POST_STATE_ERROR", message))
                        return@get
                    }

                    // 提取成功的结果
                    @Suppress("UNCHECKED_CAST")
                    val successItems = postItems.filterIsInstance<Either.Right<*>>()
                        .map { (it as Either.Right<GetUserPostsWithStatusUseCase.UserPostItem>).value }

                    logger.info("用户 Posts 查询成功: userId=$userId, count=${successItems.size}, duration=${duration}ms")

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
                    logger.error("用户 Posts 查询异常: userId=$userId, duration=${duration}ms, error=${e.message}", e)
                    throw e
                }
            }
        }  // 关闭 authenticateOptional 块

        // ========== 需要认证的路由 ==========

        authenticate("auth-jwt") {

            /**
             * POST /v1/posts
             * 创建新 Post（顶层 Post 或回复）
             */
            post {
                val startTime = System.currentTimeMillis()
                val principal = call.principal<UserPrincipal>()
                val userId = principal?.userId ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@post
                }

                try {
                    // 接收请求
                    val request = call.receive<CreatePostRequest>()
                    logger.info(
                        "收到创建 Post 请求: userId=$userId, parentId=${request.parentId}, " +
                        "mediaCount=${request.mediaUrls.size}"
                    )

                    // 先解析请求为命令（检查 MediaType 有效性）
                    val commandOrError = request.toCommand(UserId(userId))
                    val duration = System.currentTimeMillis() - startTime

                    commandOrError.fold(
                        ifLeft = { error ->
                            // 命令构建失败（如无效的 MediaType）
                            val (status, body) = error.toHttpError()
                            logger.warn(
                                "Post 创建失败: userId=$userId, error=${error.javaClass.simpleName}, " +
                                "duration=${duration}ms"
                            )
                            call.respond(status, body)
                        },
                        ifRight = { command ->
                            // 命令构建成功，调用 Use Case
                            val result = createPostUseCase(command)
                            result.fold(
                                ifLeft = { error ->
                                    val (status, body) = error.toHttpError()
                                    logger.warn(
                                        "Post 创建失败: userId=$userId, error=${error.javaClass.simpleName}, " +
                                        "duration=${duration}ms"
                                    )
                                    call.respond(status, body)
                                },
                                ifRight = { post ->
                                    logger.info(
                                        "Post 创建成功: userId=$userId, postId=${post.id.value}, " +
                                        "duration=${duration}ms"
                                    )
                                    // 返回完整的 PostDetail
                                    val detail = getPostUseCase(post.id).getOrNull()
                                    if (detail != null) {
                                        // 创建Post响应中不返回交互状态
                                        call.respond(HttpStatusCode.Created, detail.toResponse())
                                    } else {
                                        // Fallback：理论上不应该发生
                                        call.respond(HttpStatusCode.Created, mapOf("postId" to post.id.value))
                                    }
                                }
                            )
                        }
                    )

                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - startTime
                    logger.error("Post 创建异常: userId=$userId, duration=${duration}ms, error=${e.message}", e)
                    throw e
                }
            }

            /**
             * DELETE /v1/posts/{postId}
             * 删除 Post（未来可以添加权限检查）
             */
            delete("/{postId}") {
                val startTime = System.currentTimeMillis()
                val principal = call.principal<UserPrincipal>()
                val userId = principal?.userId ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@delete
                }

                val postId = call.parameters["postId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_PARAM", "缺少 postId 参数"))
                    return@delete
                }

                try {
                    logger.info("删除 Post 请求: userId=$userId, postId=$postId")

                    // TODO: 添加权限检查（只能删除自己的 Post）
                    // 这里需要先查询 Post 的 authorId，然后比对

                    // 暂时直接删除
                    // val result = deletePostUseCase(PostId(postId))
                    // 由于 Use Case 未实现，暂时返回未实现错误

                    call.respond(
                        HttpStatusCode.NotImplemented,
                        ErrorResponse("NOT_IMPLEMENTED", "删除功能暂未实现")
                    )

                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - startTime
                    logger.error("Post 删除异常: userId=$userId, postId=$postId, duration=${duration}ms, error=${e.message}", e)
                    throw e
                }
            }
        }
    }
}
