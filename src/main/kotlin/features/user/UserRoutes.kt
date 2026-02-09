package com.connor.features.user

import com.connor.core.http.ApiErrorResponse
import com.connor.core.security.UserPrincipal
import com.connor.domain.model.UserId
import com.connor.domain.model.Username
import com.connor.domain.usecase.*
import com.connor.features.post.PostListResponse
import com.connor.features.post.toResponse
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
    getUserLikesWithStatusUseCase: GetUserLikesWithStatusUseCase
) {
    route("/v1/users") {
        // ========== 公开路由（可选认证）==========

        authenticateOptional("auth-jwt") {
            /**
             * GET /v1/users/{userId}
             * 获取用户资料（通过 UserId）
             */
            get("/{userId}") {
                val userId = call.parameters["userId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MISSING_PARAM", "缺少 userId 参数"))
                    return@get
                }

                val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                val result = getUserProfileUseCase(UserId(userId), currentUserId)
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

                val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

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

                val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                val items = getUserFollowingUseCase(UserId(userId), limit + 1, offset, currentUserId).toList()

                val hasMore = items.size > limit
                val itemsToReturn = if (hasMore) items.take(limit) else items

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

                val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                val items = getUserFollowersUseCase(UserId(userId), limit + 1, offset, currentUserId).toList()

                val hasMore = items.size > limit
                val itemsToReturn = if (hasMore) items.take(limit) else items

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

                val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                val postItems = getUserPostsWithStatusUseCase(UserId(userId), limit + 1, offset, currentUserId).toList()

                // 检查错误
                val failures = postItems.filterIsInstance<arrow.core.Either.Left<*>>()
                if (failures.isNotEmpty()) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiErrorResponse("STATE_ERROR", "Failed to check interaction state")
                    )
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

                val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                val replyItems = getUserRepliesWithStatusUseCase(UserId(userId), limit + 1, offset, currentUserId).toList()

                // 检查错误
                val failures = replyItems.filterIsInstance<arrow.core.Either.Left<*>>()
                if (failures.isNotEmpty()) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiErrorResponse("STATE_ERROR", "Failed to check interaction state")
                    )
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

                val currentUserId = call.principal<UserPrincipal>()?.userId?.let { UserId(it) }

                // TODO: 后期添加隐私检查
                // if (targetUser.likesPrivacy == Private && currentUserId != userId) {
                //     return forbidden
                // }

                val likeItems = getUserLikesWithStatusUseCase(UserId(userId), limit + 1, offset, currentUserId).toList()

                // 检查错误
                val failures = likeItems.filterIsInstance<arrow.core.Either.Left<*>>()
                if (failures.isNotEmpty()) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiErrorResponse("STATE_ERROR", "Failed to check interaction state")
                    )
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
                    userId = UserId(userId),
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

                val result = followUserUseCase(UserId(followerId), UserId(followingId))
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

                val result = unfollowUserUseCase(UserId(followerId), UserId(followingId))
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
        }
    }
}
