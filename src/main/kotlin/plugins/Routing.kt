package com.connor.plugins

import com.connor.core.security.TokenService
import com.connor.core.security.UserPrincipal
import com.connor.domain.usecase.*
import com.connor.features.auth.authRoutes
import com.connor.features.media.mediaRoutes
import com.connor.features.post.bookmarkRoutes
import com.connor.features.post.likeRoutes
import com.connor.features.post.postRoutes
import com.connor.features.user.userRoutes
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject
import java.io.File

fun Application.configureRouting() {
    // Auth Use Cases
    val registerUseCase by inject<RegisterUseCase>()
    val loginUseCase by inject<LoginUseCase>()
    val tokenService by inject<TokenService>()

    // Post Use Cases
    val createPostUseCase by inject<CreatePostUseCase>()
    val getPostUseCase by inject<GetPostUseCase>()
    val getTimelineWithStatusUseCase by inject<GetTimelineWithStatusUseCase>()
    val getRepliesUseCase by inject<GetRepliesUseCase>()
    val getRepliesWithStatusUseCase by inject<GetRepliesWithStatusUseCase>()
    val getUserPostsUseCase by inject<GetUserPostsUseCase>()
    val getUserPostsWithStatusUseCase by inject<GetUserPostsWithStatusUseCase>()
    val getPostDetailWithStatusUseCase by inject<GetPostDetailWithStatusUseCase>()

    // Like Use Cases
    val likePostUseCase by inject<LikePostUseCase>()
    val unlikePostUseCase by inject<UnlikePostUseCase>()
    val getUserLikesUseCase by inject<GetUserLikesUseCase>()
    val getUserLikesWithStatusUseCase by inject<GetUserLikesWithStatusUseCase>()

    // Bookmark Use Cases
    val bookmarkPostUseCase by inject<BookmarkPostUseCase>()
    val unbookmarkPostUseCase by inject<UnbookmarkPostUseCase>()
    val getUserBookmarksUseCase by inject<GetUserBookmarksUseCase>()
    val getUserBookmarksWithStatusUseCase by inject<GetUserBookmarksWithStatusUseCase>()

    // Media Use Cases
    val uploadMediaUseCase by inject<UploadMediaUseCase>()

    // User Profile Use Cases
    val getUserProfileUseCase by inject<GetUserProfileUseCase>()
    val updateUserProfileUseCase by inject<UpdateUserProfileUseCase>()
    val followUserUseCase by inject<FollowUserUseCase>()
    val unfollowUserUseCase by inject<UnfollowUserUseCase>()
    val getUserFollowingUseCase by inject<GetUserFollowingUseCase>()
    val getUserFollowersUseCase by inject<GetUserFollowersUseCase>()
    val getUserRepliesWithStatusUseCase by inject<GetUserRepliesWithStatusUseCase>()

    // Media config (for serving static files)
    val uploadDir = environment.config.propertyOrNull("media.uploadDir")?.getString() ?: "uploads"

    routing {
        // Static file serving for uploads
        staticFiles("/uploads", File(uploadDir))

        // 公开路由 - 不需要认证
        authRoutes(registerUseCase, loginUseCase, tokenService)
        postRoutes(createPostUseCase, getPostUseCase, getTimelineWithStatusUseCase, getRepliesUseCase, getRepliesWithStatusUseCase, getUserPostsUseCase, getUserPostsWithStatusUseCase, getPostDetailWithStatusUseCase)
        likeRoutes(likePostUseCase, unlikePostUseCase, getUserLikesUseCase, getUserLikesWithStatusUseCase)
        bookmarkRoutes(bookmarkPostUseCase, unbookmarkPostUseCase, getUserBookmarksUseCase, getUserBookmarksWithStatusUseCase)
        mediaRoutes(uploadMediaUseCase)
        userRoutes(getUserProfileUseCase, updateUserProfileUseCase, followUserUseCase, unfollowUserUseCase, getUserFollowingUseCase, getUserFollowersUseCase, getUserPostsWithStatusUseCase, getUserRepliesWithStatusUseCase, getUserLikesWithStatusUseCase)

        // 健康检查
        get("/") { call.respondText("Twitter Clone API is running!") }

        // ========== 受保护路由示例 ==========
        // 所有在这个 authenticate 块内的路由都需要 JWT Token 才能访问
        authenticate("auth-jwt") {

            // 示例 1: 获取当前用户信息
            get("/v1/users/me") {
                // 从 JWT 中获取当前登录用户的 ID
                val principal = call.principal<UserPrincipal>()

                if (principal != null) {
                    call.respondText(
                        "当前登录用户 ID: ${principal.userId}",
                        status = HttpStatusCode.OK
                    )
                } else {
                    call.respond(HttpStatusCode.Unauthorized, "未授权访问")
                }
            }

            // 示例 2: 获取用户资料（更实际的用法）
            get("/v1/profile") {
                // 获取当前用户 ID
                val userId = call.principal<UserPrincipal>()?.userId

                // 这里你可以根据 userId 从数据库查询用户完整信息
                // val user = userRepository.findById(userId)

                call.respondText(
                    "用户资料页面 - User ID: $userId\n" +
                    "提示: 这个路由需要在请求头中携带 'Authorization: Bearer <token>' 才能访问",
                    status = HttpStatusCode.OK
                )
            }

            // 示例 3: 模拟更新操作
            get("/v1/posts/create") {
                val userId = call.principal<UserPrincipal>()?.userId

                call.respondText(
                    "创建帖子 - 作者 ID: $userId\n" +
                    "只有登录用户才能创建帖子！",
                    status = HttpStatusCode.OK
                )
            }
        }
    }
}
