package com.connor

import com.connor.core.coroutine.ApplicationCoroutineScope
import com.connor.core.security.TokenConfig
import com.connor.data.db.DatabaseConfig
import com.connor.data.db.DatabaseFactory
import com.connor.plugins.configureRouting
import com.connor.plugins.configureSecurity
import com.connor.plugins.configureStatusPages
import com.connor.plugins.configureWebSockets
import io.ktor.server.application.*
import io.ktor.server.netty.EngineMain
import org.koin.ktor.ext.inject

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
    configureWebSockets()      // 5. WebSocket 支持
    configureHTTP()            // 6. HTTP 配置（CORS 等）
    configureFrameworks(tokenConfig)  // 7. DI 框架
    configureRouting()         // 8. 路由（最后）

    // 注册应用停止时的清理逻辑
    environment.monitor.subscribe(ApplicationStopping) {
        val appScope by inject<ApplicationCoroutineScope>()
        appScope.shutdown()
    }
}
