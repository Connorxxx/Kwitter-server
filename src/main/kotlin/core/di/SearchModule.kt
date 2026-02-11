package com.connor.core.di

import com.connor.data.repository.ExposedSearchRepository
import com.connor.domain.repository.SearchRepository
import com.connor.domain.usecase.SearchPostsUseCase
import com.connor.domain.usecase.SearchRepliesUseCase
import com.connor.domain.usecase.SearchUsersUseCase
import org.koin.dsl.module

val searchModule = module {
    // Repository 层：Search Repository（注入 ExposedUserRepository 以复用 calculateUserStats）
    single<SearchRepository> { ExposedSearchRepository(get()) }

    // Use Case 层：搜索相关
    single { SearchPostsUseCase(get(), get()) }
    single { SearchRepliesUseCase(get(), get()) }
    single { SearchUsersUseCase(get(), get()) }
}
