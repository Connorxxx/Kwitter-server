package com.connor

import com.connor.core.di.dataModule
import com.connor.core.di.domainModule
import com.connor.core.di.mediaModule
import com.connor.core.di.notificationModule
import com.connor.core.di.securityModule
import com.connor.core.security.TokenConfig
import io.ktor.server.application.*
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureFrameworks(tokenConfig: TokenConfig) {
    install(Koin) {
        slf4jLogger()
        modules(
            dataModule,
            domainModule,
            mediaModule(this@configureFrameworks),
            securityModule(tokenConfig),
            notificationModule
        )
    }
}
