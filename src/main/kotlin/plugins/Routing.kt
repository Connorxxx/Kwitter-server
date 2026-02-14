package com.connor.plugins

import com.connor.core.security.UserPrincipal
import com.connor.core.security.sensitive
import com.connor.domain.repository.MessageRepository
import com.connor.domain.repository.NotificationRepository
import com.connor.domain.repository.UserRepository
import com.connor.domain.usecase.*
import com.connor.features.auth.authRoutes
import com.connor.features.media.mediaRoutes
import com.connor.features.post.bookmarkRoutes
import com.connor.features.post.likeRoutes
import com.connor.core.coroutine.ApplicationCoroutineScope
import com.connor.features.post.postRoutes
import com.connor.features.search.searchRoutes
import com.connor.features.user.userRoutes
import com.connor.features.messaging.messagingRoutes
import com.connor.features.notification.notificationWebSocket
import com.connor.infrastructure.websocket.WebSocketConnectionManager
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
    val refreshTokenUseCase by inject<RefreshTokenUseCase>()

    // Post Use Cases
    val createPostUseCase by inject<CreatePostUseCase>()
    val deletePostUseCase by inject<DeletePostUseCase>()
    val getPostUseCase by inject<GetPostUseCase>()
    val getTimelineWithStatusUseCase by inject<GetTimelineWithStatusUseCase>()
    val getRepliesUseCase by inject<GetRepliesUseCase>()
    val getRepliesWithStatusUseCase by inject<GetRepliesWithStatusUseCase>()
    val getUserPostsUseCase by inject<GetUserPostsUseCase>()
    val getUserPostsWithStatusUseCase by inject<GetUserPostsWithStatusUseCase>()
    val getPostDetailWithStatusUseCase by inject<GetPostDetailWithStatusUseCase>()

    // Notification Use Cases
    val broadcastPostCreatedUseCase by inject<BroadcastPostCreatedUseCase>()
    val broadcastPostLikedUseCase by inject<BroadcastPostLikedUseCase>()

    // Search Use Cases
    val searchPostsUseCase by inject<SearchPostsUseCase>()
    val searchRepliesUseCase by inject<SearchRepliesUseCase>()
    val searchUsersUseCase by inject<SearchUsersUseCase>()

    // Application Coroutine Scope
    val appScope by inject<ApplicationCoroutineScope>()

    // Like Use Cases
    val likePostUseCase by inject<LikePostUseCase>()
    val unlikePostUseCase by inject<UnlikePostUseCase>()
    val getUserLikesWithStatusUseCase by inject<GetUserLikesWithStatusUseCase>()

    // Bookmark Use Cases
    val bookmarkPostUseCase by inject<BookmarkPostUseCase>()
    val unbookmarkPostUseCase by inject<UnbookmarkPostUseCase>()
    val getUserBookmarksUseCase by inject<GetUserBookmarksUseCase>()
    val getUserBookmarksWithStatusUseCase by inject<GetUserBookmarksWithStatusUseCase>()

    // Media Use Cases
    val uploadMediaUseCase by inject<UploadMediaUseCase>()

    // Avatar Use Cases
    val uploadAvatarUseCase by inject<UploadAvatarUseCase>()
    val deleteAvatarUseCase by inject<DeleteAvatarUseCase>()

    // User Profile Use Cases
    val getUserProfileUseCase by inject<GetUserProfileUseCase>()
    val updateUserProfileUseCase by inject<UpdateUserProfileUseCase>()
    val followUserUseCase by inject<FollowUserUseCase>()
    val unfollowUserUseCase by inject<UnfollowUserUseCase>()
    val getUserFollowingUseCase by inject<GetUserFollowingUseCase>()
    val getUserFollowersUseCase by inject<GetUserFollowersUseCase>()
    val getUserRepliesWithStatusUseCase by inject<GetUserRepliesWithStatusUseCase>()

    // Block Use Cases
    val blockUserUseCase by inject<BlockUserUseCase>()
    val unblockUserUseCase by inject<UnblockUserUseCase>()

    // Messaging Use Cases
    val sendMessageUseCase by inject<SendMessageUseCase>()
    val getConversationsUseCase by inject<GetConversationsUseCase>()
    val getMessagesUseCase by inject<GetMessagesUseCase>()
    val markConversationReadUseCase by inject<MarkConversationReadUseCase>()
    val notifyNewMessageUseCase by inject<NotifyNewMessageUseCase>()
    val deleteMessageUseCase by inject<DeleteMessageUseCase>()
    val recallMessageUseCase by inject<RecallMessageUseCase>()

    // WebSocket Connection Manager
    val connectionManager by inject<WebSocketConnectionManager>()

    // Repositories
    val userRepository by inject<UserRepository>()
    val notificationRepository by inject<NotificationRepository>()
    val messageRepository by inject<MessageRepository>()

    // Media config (for serving static files)
    val uploadDir = environment.config.propertyOrNull("media.uploadDir")?.getString() ?: "uploads"

    routing {
        // Static file serving for uploads
        staticFiles("/uploads", File(uploadDir))

        // ========== 公开路由 - 不需要认证 ==========
        authRoutes(registerUseCase, loginUseCase, refreshTokenUseCase)
        postRoutes(createPostUseCase, deletePostUseCase, getPostUseCase, getTimelineWithStatusUseCase, getRepliesUseCase, getRepliesWithStatusUseCase, getUserPostsUseCase, getUserPostsWithStatusUseCase, getPostDetailWithStatusUseCase, broadcastPostCreatedUseCase, appScope)
        likeRoutes(likePostUseCase, unlikePostUseCase, broadcastPostLikedUseCase, appScope)
        bookmarkRoutes(bookmarkPostUseCase, unbookmarkPostUseCase, getUserBookmarksUseCase, getUserBookmarksWithStatusUseCase)
        mediaRoutes(uploadMediaUseCase)
        userRoutes(getUserProfileUseCase, updateUserProfileUseCase, followUserUseCase, unfollowUserUseCase, getUserFollowingUseCase, getUserFollowersUseCase, getUserPostsWithStatusUseCase, getUserRepliesWithStatusUseCase, getUserLikesWithStatusUseCase, uploadAvatarUseCase, deleteAvatarUseCase, blockUserUseCase, unblockUserUseCase)
        searchRoutes(searchPostsUseCase, searchRepliesUseCase, searchUsersUseCase)
        messagingRoutes(sendMessageUseCase, getConversationsUseCase, getMessagesUseCase, markConversationReadUseCase, notifyNewMessageUseCase, deleteMessageUseCase, recallMessageUseCase, appScope)

        // WebSocket 实时通知路由
        notificationWebSocket(connectionManager, notificationRepository, messageRepository)

        // 健康检查
        get("/") { call.respondText("Twitter Clone API is running!") }

        // ========== 普通认证路由（快，无状态，不查库） ==========
        authenticate("auth-jwt") {

            get("/v1/users/me") {
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

            // ========== 敏感路由（慢，有状态，强制查库） ==========
            sensitive(userRepository) {
                // 示例：修改密码、删除账号、确认支付等高风险操作
                // post("/v1/auth/change-password") { ... }
                // delete("/v1/account") { ... }
                // post("/v1/payment/confirm") { ... }
            }
        }
    }
}
