package com.connor.features.user

import com.connor.core.http.ApiErrorResponse
import com.connor.core.security.UserPrincipal
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import com.connor.domain.model.Username
import com.connor.domain.usecase.*
import com.connor.features.post.PostListResponse
import com.connor.features.post.toResponse
import com.connor.plugins.tryResolvePrincipal
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.toList
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("UserRoutes")

fun Route.userRoutes(
    getUserProfileUseCase: GetUserProfileUseCase,
    updateUserProfileUseCase: UpdateUserProfileUseCase,
    followUserUseCase: FollowUserUseCase,
    unfollowUserUseCase: UnfollowUserUseCase,
    getUserFollowingUseCase: GetUserFollowingUseCase,
    getUserFollowersUseCase: GetUserFollowersUseCase,
    getUserPostsWithStatusUseCase: GetUserPostsWithStatusUseCase,
    getUserRepliesWithStatusUseCase: GetUserRepliesWithStatusUseCase,
    getUserLikesWithStatusUseCase: GetUserLikesWithStatusUseCase,
    uploadAvatarUseCase: UploadAvatarUseCase,
    deleteAvatarUseCase: DeleteAvatarUseCase,
    blockUserUseCase: BlockUserUseCase,
    unblockUserUseCase: UnblockUserUseCase
) {
    route("/v1/users") {
        // ========== 公开路由（软鉴权）==========
            /**
             * GET /v1/users/{userId}
             * 获取用户资料（通过 UserId）
             */
            get("/{userId}") {
                val userId = call.parameters["userId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 userId 参数"))
                    return@get
                }

                val currentUserId = call.tryResolvePrincipal()?.userId?.let { UserId(it.toLong()) }

                val result = getUserProfileUseCase(UserId(userId.toLong()), currentUserId)
                result.fold(
                    ifLeft = { error ->
                        val (status, body) = error.toHttpError()
                        call.respond(status, body)
                    },
                    ifRight = { profileView ->
                        call.respond(HttpStatusCode.OK, profileView.toResponse())
                    }
                )
            }

            /**
             * GET /v1/users/username/{username}
             * 获取用户资料（通过 Username）
             */
            get("/username/{username}") {
                val usernameStr = call.parameters["username"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 username 参数"))
                    return@get
                }

                val currentUserId = call.tryResolvePrincipal()?.userId?.let { UserId(it.toLong()) }

                // 解析 Username
                val usernameResult = Username(usernameStr)
                usernameResult.fold(
                    ifLeft = { error ->
                        val (status, body) = error.toHttpError()
                        call.respond(status, body)
                    },
                    ifRight = { username ->
                        val result = getUserProfileUseCase.byUsername(username, currentUserId)
                        result.fold(
                            ifLeft = { error ->
                                val (status, body) = error.toHttpError()
                                call.respond(status, body)
                            },
                            ifRight = { profileView ->
                                call.respond(HttpStatusCode.OK, profileView.toResponse())
                            }
                        )
                    }
                )
            }

            /**
             * GET /v1/users/{userId}/following?limit=20&offset=0
             * 获取用户关注列表
             */
            get("/{userId}/following") {
                val userId = call.parameters["userId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 userId 参数"))
                    return@get
                }
                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val limit = rawLimit.coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

                val currentUserId = call.tryResolvePrincipal()?.userId?.let { UserId(it.toLong()) }

                val items = getUserFollowingUseCase(UserId(userId.toLong()), limit, offset, currentUserId).toList()

                // 检查是否有 UserNotFound 错误
                val userNotFoundError = items.firstOrNull {
                    it.isLeft() && (it as arrow.core.Either.Left).value is com.connor.domain.failure.UserError.UserNotFound
                }
                if (userNotFoundError != null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiErrorResponse("USER_NOT_FOUND", "用户不存在")
                    )
                    return@get
                }

                // 提取成功的结果
                val successItems = items.mapNotNull { it.getOrNull() }
                val hasMore = successItems.size > limit
                val itemsToReturn = if (hasMore) successItems.take(limit) else successItems

                call.respond(
                    HttpStatusCode.OK,
                    UserListResponse(
                        users = itemsToReturn.map { it.toDto() },
                        hasMore = hasMore
                    )
                )
            }

            /**
             * GET /v1/users/{userId}/followers?limit=20&offset=0
             * 获取用户粉丝列表
             */
            get("/{userId}/followers") {
                val userId = call.parameters["userId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 userId 参数"))
                    return@get
                }
                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val limit = rawLimit.coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

                val currentUserId = call.tryResolvePrincipal()?.userId?.let { UserId(it.toLong()) }

                val items = getUserFollowersUseCase(UserId(userId.toLong()), limit, offset, currentUserId).toList()

                // 检查是否有 UserNotFound 错误
                val userNotFoundError = items.firstOrNull {
                    it.isLeft() && (it as arrow.core.Either.Left).value is com.connor.domain.failure.UserError.UserNotFound
                }
                if (userNotFoundError != null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiErrorResponse("USER_NOT_FOUND", "用户不存在")
                    )
                    return@get
                }

                // 提取成功的结果
                val successItems = items.mapNotNull { it.getOrNull() }
                val hasMore = successItems.size > limit
                val itemsToReturn = if (hasMore) successItems.take(limit) else successItems

                call.respond(
                    HttpStatusCode.OK,
                    UserListResponse(
                        users = itemsToReturn.map { it.toDto() },
                        hasMore = hasMore
                    )
                )
            }

            /**
             * GET /v1/users/{userId}/posts?limit=20&offset=0
             * 获取用户的 Posts（不包括回复）
             */
            get("/{userId}/posts") {
                val userId = call.parameters["userId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 userId 参数"))
                    return@get
                }
                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val limit = rawLimit.coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val beforeId = call.request.queryParameters["beforeId"]?.toLongOrNull()?.let { PostId(it) }

                val currentUserId = call.tryResolvePrincipal()?.userId?.let { UserId(it.toLong()) }

                val postItems = getUserPostsWithStatusUseCase(UserId(userId.toLong()), limit, offset, currentUserId, beforeId).toList()

                // 检查错误
                val failures = postItems.filterIsInstance<arrow.core.Either.Left<*>>()
                if (failures.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    val error = (failures.first() as arrow.core.Either.Left<GetUserPostsWithStatusUseCase.UserPostError>).value
                    val (status, message) = when (error) {
                        is GetUserPostsWithStatusUseCase.UserPostError.UserNotFound -> {
                            HttpStatusCode.NotFound to "用户不存在"
                        }
                        is GetUserPostsWithStatusUseCase.UserPostError.LikesCheckFailed -> {
                            HttpStatusCode.InternalServerError to "Failed to check interaction state"
                        }
                        is GetUserPostsWithStatusUseCase.UserPostError.BookmarksCheckFailed -> {
                            HttpStatusCode.InternalServerError to "Failed to check interaction state"
                        }
                    }
                    call.respond(status, ApiErrorResponse("USER_POST_ERROR", message))
                    return@get
                }

                val successItems = postItems.mapNotNull { it.getOrNull() }
                val hasMore = successItems.size > limit
                val itemsToReturn = if (hasMore) successItems.take(limit) else successItems

                call.respond(
                    HttpStatusCode.OK,
                    PostListResponse(
                        posts = itemsToReturn.map { item ->
                            item.postDetail.toResponse(
                                isLikedByCurrentUser = item.isLikedByCurrentUser,
                                isBookmarkedByCurrentUser = item.isBookmarkedByCurrentUser
                            )
                        },
                        hasMore = hasMore
                    )
                )
            }

            /**
             * GET /v1/users/{userId}/replies?limit=20&offset=0
             * 获取用户的回复
             */
            get("/{userId}/replies") {
                val userId = call.parameters["userId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 userId 参数"))
                    return@get
                }
                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val limit = rawLimit.coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val beforeId = call.request.queryParameters["beforeId"]?.toLongOrNull()?.let { PostId(it) }

                val currentUserId = call.tryResolvePrincipal()?.userId?.let { UserId(it.toLong()) }

                val replyItems = getUserRepliesWithStatusUseCase(UserId(userId.toLong()), limit, offset, currentUserId, beforeId).toList()

                // 检查错误
                val failures = replyItems.filterIsInstance<arrow.core.Either.Left<*>>()
                if (failures.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    val error = (failures.first() as arrow.core.Either.Left<GetUserRepliesWithStatusUseCase.UserReplyError>).value
                    val (status, message) = when (error) {
                        is GetUserRepliesWithStatusUseCase.UserReplyError.UserNotFound -> {
                            HttpStatusCode.NotFound to "用户不存在"
                        }
                        is GetUserRepliesWithStatusUseCase.UserReplyError.LikesCheckFailed -> {
                            HttpStatusCode.InternalServerError to "Failed to check interaction state"
                        }
                        is GetUserRepliesWithStatusUseCase.UserReplyError.BookmarksCheckFailed -> {
                            HttpStatusCode.InternalServerError to "Failed to check interaction state"
                        }
                    }
                    call.respond(status, ApiErrorResponse("USER_REPLY_ERROR", message))
                    return@get
                }

                val successItems = replyItems.mapNotNull { it.getOrNull() }
                val hasMore = successItems.size > limit
                val itemsToReturn = if (hasMore) successItems.take(limit) else successItems

                call.respond(
                    HttpStatusCode.OK,
                    PostListResponse(
                        posts = itemsToReturn.map { item ->
                            item.postDetail.toResponse(
                                isLikedByCurrentUser = item.isLikedByCurrentUser,
                                isBookmarkedByCurrentUser = item.isBookmarkedByCurrentUser
                            )
                        },
                        hasMore = hasMore
                    )
                )
            }

            /**
             * GET /v1/users/{userId}/likes?limit=20&offset=0
             * 获取用户的点赞列表
             * 注意：后期可以添加隐私设置，当前默认公开
             */
            get("/{userId}/likes") {
                val userId = call.parameters["userId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 userId 参数"))
                    return@get
                }
                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val limit = rawLimit.coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

                val currentUserId = call.tryResolvePrincipal()?.userId?.let { UserId(it.toLong()) }

                // TODO: 后期添加隐私检查
                // if (targetUser.likesPrivacy == Private && currentUserId != userId) {
                //     return forbidden
                // }

                val likeItems = getUserLikesWithStatusUseCase(UserId(userId.toLong()), limit, offset, currentUserId).toList()

                // 检查错误
                val failures = likeItems.filterIsInstance<arrow.core.Either.Left<*>>()
                if (failures.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    val error = (failures.first() as arrow.core.Either.Left<GetUserLikesWithStatusUseCase.UserLikesError>).value
                    val (status, message) = when (error) {
                        is GetUserLikesWithStatusUseCase.UserLikesError.UserNotFound -> {
                            HttpStatusCode.NotFound to "用户不存在"
                        }
                        is GetUserLikesWithStatusUseCase.UserLikesError.LikesCheckFailed -> {
                            HttpStatusCode.InternalServerError to "Failed to check interaction state"
                        }
                        is GetUserLikesWithStatusUseCase.UserLikesError.BookmarksCheckFailed -> {
                            HttpStatusCode.InternalServerError to "Failed to check interaction state"
                        }
                    }
                    call.respond(status, ApiErrorResponse("USER_LIKES_ERROR", message))
                    return@get
                }

                val successItems = likeItems.mapNotNull { it.getOrNull() }
                val hasMore = successItems.size > limit
                val itemsToReturn = if (hasMore) successItems.take(limit) else successItems

                call.respond(
                    HttpStatusCode.OK,
                    PostListResponse(
                        posts = itemsToReturn.map { item ->
                            item.postDetail.toResponse(
                                isLikedByCurrentUser = item.isLikedByCurrentUser,
                                isBookmarkedByCurrentUser = item.isBookmarkedByCurrentUser
                            )
                        },
                        hasMore = hasMore
                    )
                )
            }
        // ========== 需要认证的路由 ==========

        authenticate("auth-jwt") {
            /**
             * PATCH /v1/users/me
             * 更新当前用户的资料
             */
            patch("/me") {
                val principal = call.principal<UserPrincipal>()
                val userId = principal?.userId ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@patch
                }

                val request = call.receive<UpdateProfileRequest>()

                val command = UpdateProfileCommand(
                    userId = UserId(userId.toLong()),
                    username = request.username,
                    displayName = request.displayName,
                    bio = request.bio,
                    avatarUrl = request.avatarUrl
                )

                val result = updateUserProfileUseCase(command)
                result.fold(
                    ifLeft = { error ->
                        val (status, body) = error.toHttpError()
                        call.respond(status, body)
                    },
                    ifRight = { user ->
                        call.respond(HttpStatusCode.OK, user.toDto())
                    }
                )
            }

            /**
             * POST /v1/users/me/avatar
             * 上传/更换头像（multipart form data）
             */
            post("/me/avatar") {
                val principal = call.principal<UserPrincipal>()
                val userId = principal?.userId ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@post
                }

                try {
                    val multipart = call.receiveMultipart()
                    var part: PartData? = multipart.readPart()
                    while (part != null) {
                        when (part) {
                            is PartData.FileItem -> {
                                val contentType = part.contentType?.toString() ?: ""
                                val bytes = part.provider().readRemaining().readByteArray()

                                val result = uploadAvatarUseCase(UserId(userId.toLong()), contentType, bytes)
                                result.fold(
                                    ifLeft = { error ->
                                        val (status, body) = error.toHttpError()
                                        call.respond(status, body)
                                    },
                                    ifRight = { avatarUrl ->
                                        call.respond(HttpStatusCode.Created, AvatarUploadResponse(avatarUrl))
                                    }
                                )

                                part.dispose()
                                return@post
                            }
                            else -> { /* skip non-file parts */ }
                        }
                        part.dispose()
                        part = multipart.readPart()
                    }

                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorResponse("NO_FILE_PROVIDED", "请提供头像文件")
                    )
                } catch (e: Exception) {
                    logger.error("Unexpected error during avatar upload", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiErrorResponse("AVATAR_UPLOAD_ERROR", "头像上传失败，请稍后重试")
                    )
                }
            }

            /**
             * DELETE /v1/users/me/avatar
             * 删除头像
             */
            delete("/me/avatar") {
                val principal = call.principal<UserPrincipal>()
                val userId = principal?.userId ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@delete
                }

                val result = deleteAvatarUseCase(UserId(userId.toLong()))
                result.fold(
                    ifLeft = { error ->
                        val (status, body) = error.toHttpError()
                        call.respond(status, body)
                    },
                    ifRight = {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "头像已删除"))
                    }
                )
            }

            /**
             * POST /v1/users/{userId}/follow
             * 关注用户
             */
            post("/{userId}/follow") {
                val principal = call.principal<UserPrincipal>()
                val followerId = principal?.userId ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@post
                }

                val followingId = call.parameters["userId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 userId 参数"))
                    return@post
                }

                val result = followUserUseCase(UserId(followerId.toLong()), UserId(followingId.toLong()))
                result.fold(
                    ifLeft = { error ->
                        val (status, body) = error.toHttpError()
                        call.respond(status, body)
                    },
                    ifRight = {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "关注成功"))
                    }
                )
            }

            /**
             * DELETE /v1/users/{userId}/follow
             * 取消关注
             */
            delete("/{userId}/follow") {
                val principal = call.principal<UserPrincipal>()
                val followerId = principal?.userId ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@delete
                }

                val followingId = call.parameters["userId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 userId 参数"))
                    return@delete
                }

                val result = unfollowUserUseCase(UserId(followerId.toLong()), UserId(followingId.toLong()))
                result.fold(
                    ifLeft = { error ->
                        val (status, body) = error.toHttpError()
                        call.respond(status, body)
                    },
                    ifRight = {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "取消关注成功"))
                    }
                )
            }

            /**
             * POST /v1/users/{userId}/block
             * 拉黑用户
             */
            post("/{userId}/block") {
                val principal = call.principal<UserPrincipal>()
                val blockerId = principal?.userId ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@post
                }

                val blockedId = call.parameters["userId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 userId 参数"))
                    return@post
                }

                val result = blockUserUseCase(UserId(blockerId.toLong()), UserId(blockedId.toLong()))
                result.fold(
                    ifLeft = { error ->
                        val (status, body) = error.toHttpError()
                        call.respond(status, body)
                    },
                    ifRight = {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "拉黑成功"))
                    }
                )
            }

            /**
             * DELETE /v1/users/{userId}/block
             * 取消拉黑
             */
            delete("/{userId}/block") {
                val principal = call.principal<UserPrincipal>()
                val blockerId = principal?.userId ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("UNAUTHORIZED", "未授权访问"))
                    return@delete
                }

                val blockedId = call.parameters["userId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 userId 参数"))
                    return@delete
                }

                val result = unblockUserUseCase(UserId(blockerId.toLong()), UserId(blockedId.toLong()))
                result.fold(
                    ifLeft = { error ->
                        val (status, body) = error.toHttpError()
                        call.respond(status, body)
                    },
                    ifRight = {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "取消拉黑成功"))
                    }
                )
            }
        }
    }
}
