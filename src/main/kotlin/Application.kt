package com.connor

import com.connor.core.security.TokenConfig
import com.connor.data.db.DatabaseConfig
import com.connor.data.db.DatabaseFactory
import com.connor.plugins.configureRouting
import com.connor.plugins.configureSecurity
import com.connor.plugins.configureStatusPages
import io.ktor.server.application.*
import io.ktor.server.netty.EngineMain

fun main(args: Array<String>) = EngineMain.main(args)

fun Application.module() {
    // 从 application.yaml 读取 JWT 配置
    val tokenConfig = TokenConfig(
        domain = environment.config.property("jwt.domain").getString(),
        audience = environment.config.property("jwt.audience").getString(),
        secret = environment.config.property("jwt.secret").getString(),
        realm = environment.config.property("jwt.realm").getString()
    )

    val dbUrl = environment.config.property("storage.jdbcUrl").getString()
    val dbUser = environment.config.property("storage.user").getString()
    val dbPassword = environment.config.property("storage.password").getString()

    DatabaseFactory.init(DatabaseConfig(dbUrl, dbUser, dbPassword))

    // 配置插件（顺序很重要）
    configureMonitoring()      // 1. 日志记录
    configureStatusPages()     // 2. 全局异常处理
    configureSerialization()   // 3. JSON 序列化
    configureSecurity(tokenConfig)  // 4. JWT 认证
    configureHTTP()            // 5. HTTP 配置（CORS 等）
    configureFrameworks(tokenConfig)  // 6. DI 框架
    configureRouting()         // 7. 路由（最后）
}
