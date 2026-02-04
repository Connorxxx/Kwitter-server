package com.connor.core.di

import com.connor.core.security.TokenConfig
import com.connor.core.security.TokenService
import com.connor.domain.service.PasswordHasher
import com.connor.domain.usecase.RegisterUseCase
import com.connor.infrastructure.service.BCryptPasswordHasher
import org.koin.dsl.module

val domainModule = module {
    // Service 层：密码哈希服务
    single<PasswordHasher> { BCryptPasswordHasher() }

    // Use Case 层：注册用例
    single { RegisterUseCase(get(), get()) }
}

// Security 模块：JWT 相关配置
fun securityModule(tokenConfig: TokenConfig) = module {
    single { tokenConfig }
    single { TokenService(get()) }
}
