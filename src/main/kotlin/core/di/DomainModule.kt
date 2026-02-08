package com.connor.core.di

import com.connor.core.security.TokenConfig
import com.connor.core.security.TokenService
import com.connor.domain.service.PasswordHasher
import com.connor.domain.usecase.*
import com.connor.infrastructure.service.BCryptPasswordHasher
import org.koin.dsl.module

val domainModule = module {
    // Service 层：密码哈希服务
    single<PasswordHasher> { BCryptPasswordHasher() }

    // Use Case 层：认证相关
    single { RegisterUseCase(get(), get()) }
    single { LoginUseCase(get(), get()) }

    // Use Case 层：Post 相关
    single { CreatePostUseCase(get()) }
    single { GetPostUseCase(get()) }
    single { GetTimelineUseCase(get()) }
    single { GetTimelineWithStatusUseCase(get()) }
    single { GetRepliesUseCase(get()) }
    single { GetUserPostsUseCase(get()) }
    single { GetPostDetailWithStatusUseCase(get()) }

    // Use Case 层：Like 相关
    single { LikePostUseCase(get()) }
    single { UnlikePostUseCase(get()) }
    single { GetUserLikesUseCase(get()) }

    // Use Case 层：Bookmark 相关
    single { BookmarkPostUseCase(get()) }
    single { UnbookmarkPostUseCase(get()) }
    single { GetUserBookmarksUseCase(get()) }
}

// Security 模块：JWT 相关配置
fun securityModule(tokenConfig: TokenConfig) = module {
    single { tokenConfig }
    single { TokenService(get()) }
}
