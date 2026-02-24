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

private fun Application.readConfig(
    configPath: String,
    envName: String,
    defaultValue: String? = null
): String {
    val envValue = System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }
    if (envValue != null) return envValue

    val configValue = environment.config.propertyOrNull(configPath)?.getString()?.trim()?.takeIf { it.isNotEmpty() }
    if (configValue != null) return configValue

    return defaultValue
        ?: error("Missing configuration: '$configPath' or environment variable '$envName'")
}

fun Application.module() {
    // 环境变量优先，其次回退到 application.yaml，便于 Docker 部署
    val tokenConfig = TokenConfig(
        domain = readConfig("jwt.domain", "JWT_DOMAIN"),
        audience = readConfig("jwt.audience", "JWT_AUDIENCE"),
        secret = readConfig("jwt.secret", "JWT_SECRET"),
        realm = readConfig("jwt.realm", "JWT_REALM")
    )

    val dbUrl = readConfig("storage.jdbcUrl", "DB_JDBC_URL")
    val dbUser = readConfig("storage.user", "DB_USER")
    val dbPassword = readConfig("storage.password", "DB_PASSWORD")

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
