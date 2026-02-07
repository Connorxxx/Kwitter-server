package com.connor.core.di

import com.connor.data.repository.ExposedPostRepository
import com.connor.data.repository.ExposedUserRepository
import com.connor.domain.repository.PostRepository
import com.connor.domain.repository.UserRepository
import org.koin.dsl.module

val dataModule = module {
    // 绑定接口到实现
    single<UserRepository> { ExposedUserRepository() }
    single<PostRepository> { ExposedPostRepository(get()) }

    // 如果有 DatabaseConfig 之类的也可以在这里 bind
}