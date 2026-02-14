package com.connor.core.di

import com.connor.core.security.RefreshTokenService
import com.connor.core.security.TokenConfig
import com.connor.core.security.TokenService
import com.connor.domain.service.AuthTokenConfig
import com.connor.domain.service.PasswordHasher
import com.connor.domain.service.SessionNotifier
import com.connor.domain.service.TokenHasher
import com.connor.domain.service.TokenIssuer
import com.connor.domain.usecase.*
import com.connor.infrastructure.service.BCryptPasswordHasher
import com.connor.infrastructure.websocket.WebSocketSessionNotifier
import org.koin.dsl.module

val domainModule = module {
    // Service 层：密码哈希服务
    single<PasswordHasher> { BCryptPasswordHasher() }

    // Use Case 层：认证相关
    single { RegisterUseCase(get(), get()) }
    single { LoginUseCase(get(), get()) }
    single { RefreshTokenUseCase(get(), get(), get(), get(), get(), get()) }

    // Use Case 层：Post 相关
    single { CreatePostUseCase(get()) }
    single { DeletePostUseCase(get()) }
    single { GetPostUseCase(get()) }
    single { GetTimelineWithStatusUseCase(get(), get()) }
    single { GetRepliesUseCase(get()) }
    single { GetRepliesWithStatusUseCase(get(), get()) }
    single { GetUserPostsUseCase(get()) }
    single { GetUserPostsWithStatusUseCase(get(), get()) }
    single { GetPostDetailWithStatusUseCase(get(), get()) }

    // Use Case 层：Like 相关
    single { LikePostUseCase(get()) }
    single { UnlikePostUseCase(get()) }
    single { GetUserLikesUseCase(get()) }
    single { GetUserLikesWithStatusUseCase(get(), get()) }

    // Use Case 层：Bookmark 相关
    single { BookmarkPostUseCase(get()) }
    single { UnbookmarkPostUseCase(get()) }
    single { GetUserBookmarksUseCase(get()) }
    single { GetUserBookmarksWithStatusUseCase(get(), get()) }

    // Use Case 层：User Profile 相关
    single { GetUserProfileUseCase(get()) }
    single { UpdateUserProfileUseCase(get()) }
    single { FollowUserUseCase(get()) }
    single { UnfollowUserUseCase(get()) }
    single { GetUserFollowingUseCase(get()) }
    single { GetUserFollowersUseCase(get()) }
    single { GetUserRepliesWithStatusUseCase(get(), get()) }

    // Use Case 层：Block 相关
    single { BlockUserUseCase(get()) }
    single { UnblockUserUseCase(get()) }
}

// Security 模块：JWT 相关配置 + Domain Port 绑定
fun securityModule(tokenConfig: TokenConfig) = module {
    single { tokenConfig }
    single { TokenService(get()) }
    single { RefreshTokenService() }

    // Domain Port 绑定：将 core/infrastructure 实现适配为 domain 接口
    single<TokenIssuer> { get<TokenService>() }
    single<TokenHasher> { get<RefreshTokenService>() }
    single<SessionNotifier> { WebSocketSessionNotifier(get()) }
    single {
        AuthTokenConfig(
            accessTokenExpiresInMs = tokenConfig.expiresIn,
            refreshTokenExpiresInMs = tokenConfig.refreshTokenExpiresIn,
            refreshTokenGracePeriodMs = tokenConfig.refreshTokenGracePeriod
        )
    }
}
