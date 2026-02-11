package com.connor.core.di

import com.connor.data.repository.ExposedPostRepository
import com.connor.data.repository.ExposedRefreshTokenRepository
import com.connor.data.repository.ExposedUserRepository
import com.connor.domain.repository.PostRepository
import com.connor.domain.repository.RefreshTokenRepository
import com.connor.domain.repository.UserRepository
import org.koin.dsl.module

val dataModule = module {
    // 先注册具体实现，供需要 concrete type 的模块复用（如 SearchModule）
    single { ExposedUserRepository() }
    // 接口绑定到同一个 singleton 实例，避免创建多个仓储实例
    single<UserRepository> { get<ExposedUserRepository>() }
    single<PostRepository> { ExposedPostRepository() }
    single<RefreshTokenRepository> { ExposedRefreshTokenRepository() }
}
