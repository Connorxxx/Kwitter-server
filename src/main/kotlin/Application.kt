package com.connor

import com.connor.core.security.TokenConfig
import com.connor.plugins.configureRouting
import com.connor.plugins.configureSecurity
import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    // 从 application.yaml 读取 JWT 配置
    val tokenConfig = TokenConfig(
        domain = environment.config.property("jwt.domain").getString(),
        audience = environment.config.property("jwt.audience").getString(),
        secret = environment.config.property("jwt.secret").getString(),
        realm = environment.config.property("jwt.realm").getString()
    )

    configureSerialization()
    configureDatabases()
    configureSecurity(tokenConfig)
    configureMonitoring()
    configureHTTP()
    configureFrameworks()
    configureRouting()
}
