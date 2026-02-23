# Auth 客户端接入指南

**更新时间**: 2026-02-11
**适用**: Android (Kotlin) / iOS (Swift) / Web (TypeScript)
**前置阅读**: [auth-refresh-token-system.md](./auth-refresh-token-system.md)

---

## 1. 快速接入清单

- [ ] 登录/注册后保存 `token`、`refreshToken`、`expiresIn`
- [ ] 每个请求带 `Authorization: Bearer <token>` 请求头
- [ ] 拦截 401 响应 → 自动调用 `/v1/auth/refresh` → 重试原请求
- [ ] 刷新成功后用新的 `token` 和 `refreshToken` 替换旧值
- [ ] 刷新失败（任何 401 错误码）→ 清除 token → 跳转登录页
- [ ] WebSocket 监听 `auth_revoked` 消息 → 强制下线
- [ ] Refresh Token 安全存储（Android: EncryptedSharedPreferences / iOS: Keychain）

---

## 2. Token 存储

### 2.1 需要持久化的数据

```kotlin
data class AuthTokens(
    val accessToken: String,      // JWT，3分钟有效（配合服务端 15s leeway，实际窗口 ~3:15）
    val refreshToken: String,     // Refresh Token，14天有效
    val expiresIn: Long,          // JWT有效期毫秒数 (180000)
    val obtainedAt: Long          // 获取时间戳（用于判断是否需要提前刷新）
)
```

### 2.2 安全存储（Android）

```kotlin
// ❌ 不安全：SharedPreferences 明文存储
prefs.putString("refresh_token", token)

// ✅ 安全：EncryptedSharedPreferences
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val securePrefs = EncryptedSharedPreferences.create(
    context,
    "auth_prefs",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

securePrefs.edit()
    .putString("access_token", tokens.accessToken)
    .putString("refresh_token", tokens.refreshToken)
    .putLong("expires_in", tokens.expiresIn)
    .putLong("obtained_at", System.currentTimeMillis())
    .apply()
```

### 2.3 安全存储（iOS）

```swift
// Keychain 存储 refresh token
let query: [String: Any] = [
    kSecClass as String: kSecClassGenericPassword,
    kSecAttrAccount as String: "refresh_token",
    kSecValueData as String: refreshToken.data(using: .utf8)!
]
SecItemAdd(query as CFDictionary, nil)
```

---

## 3. HTTP 拦截器（自动刷新）

### 3.1 核心逻辑

```
请求 → 添加 JWT Header → 发送
  ↓
响应 401?
  ├── 否 → 返回响应
  └── 是 → 有 refresh token?
        ├── 否 → 跳转登录
        └── 是 → POST /v1/auth/refresh
              ├── 成功 → 保存新 token → 重试原请求
              └── 失败 → 清除 token → 跳转登录
```

### 3.2 Android (OkHttp Interceptor)

```kotlin
class AuthInterceptor(
    private val tokenStore: TokenStore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // 跳过不需要认证的请求
        if (request.url.encodedPath.startsWith("/v1/auth/")) {
            return chain.proceed(request)
        }

        // 添加 JWT
        val token = tokenStore.getAccessToken()
        val authenticatedRequest = if (token != null) {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            request
        }

        return chain.proceed(authenticatedRequest)
    }
}
```

### 3.3 Android (OkHttp Authenticator — 处理 401)

```kotlin
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi  // 用独立的 OkHttpClient，避免循环拦截
) : Authenticator {

    // 防止并发刷新：使用锁
    private val refreshLock = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // 如果已经重试过，不再重试（防止无限循环）
        if (response.request.header("X-Retry") != null) {
            tokenStore.clear()
            return null  // 返回 null → 触发登录
        }

        return runBlocking {
            refreshLock.withLock {
                // Double-check: 可能其他线程已经刷新成功
                val currentToken = tokenStore.getAccessToken()
                val requestToken = response.request.header("Authorization")
                    ?.removePrefix("Bearer ")

                if (currentToken != null && currentToken != requestToken) {
                    // 其他线程已经刷新了 token，用新 token 重试
                    return@runBlocking response.request.newBuilder()
                        .header("Authorization", "Bearer $currentToken")
                        .header("X-Retry", "1")
                        .build()
                }

                // 执行刷新
                val refreshToken = tokenStore.getRefreshToken() ?: run {
                    tokenStore.clear()
                    return@runBlocking null
                }

                try {
                    val result = authApi.refresh(RefreshRequest(refreshToken))
                    tokenStore.save(result.token, result.refreshToken, result.expiresIn)

                    // 用新 token 重试原请求
                    response.request.newBuilder()
                        .header("Authorization", "Bearer ${result.token}")
                        .header("X-Retry", "1")
                        .build()
                } catch (e: Exception) {
                    tokenStore.clear()
                    null  // 刷新失败 → 触发登录
                }
            }
        }
    }
}
```

### 3.4 OkHttpClient 配置

```kotlin
// ⚠️ 重要：用于 refresh 的 client 不能装 TokenAuthenticator（避免死循环）
val refreshClient = OkHttpClient.Builder()
    .build()

val refreshApi = Retrofit.Builder()
    .baseUrl(BASE_URL)
    .client(refreshClient)
    .build()
    .create(AuthApi::class.java)

// 主 client
val mainClient = OkHttpClient.Builder()
    .addInterceptor(AuthInterceptor(tokenStore))
    .authenticator(TokenAuthenticator(tokenStore, refreshApi))
    .build()
```

### 3.5 Ktor Client (KMP/CMP 项目)

```kotlin
val httpClient = HttpClient(engine) {
    install(ContentNegotiation) {
        json()
    }

    install(Auth) {
        bearer {
            loadTokens {
                val accessToken = tokenStore.getAccessToken()
                val refreshToken = tokenStore.getRefreshToken()
                if (accessToken != null && refreshToken != null) {
                    BearerTokens(accessToken, refreshToken)
                } else {
                    null
                }
            }

            refreshTokens {
                val refreshToken = oldTokens?.refreshToken ?: return@refreshTokens null
                try {
                    val response = client.post("$BASE_URL/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(refreshToken))
                        markAsRefreshTokenRequest()  // ← 防止循环
                    }.body<TokenResponse>()

                    tokenStore.save(response.token, response.refreshToken, response.expiresIn)
                    BearerTokens(response.token, response.refreshToken)
                } catch (e: Exception) {
                    tokenStore.clear()
                    null
                }
            }

            sendWithoutRequest { request ->
                // 这些路径不需要 token
                !request.url.encodedPath.startsWith("/v1/auth/")
            }
        }
    }
}
```

---

## 4. WebSocket 监听

### 4.1 连接与认证

WebSocket 连接需要在请求头中携带 JWT：

```
GET ws://localhost:8080/v1/notifications/ws
Authorization: Bearer <jwt>
```

### 4.2 处理认证事件

```kotlin
// Android (OkHttp WebSocket)
override fun onMessage(webSocket: WebSocket, text: String) {
    val message = json.decodeFromString<WebSocketMessage>(text)

    when (message.type) {
        "auth_revoked" -> {
            // 服务端强制下线
            tokenStore.clear()
            webSocket.close(1000, "Auth revoked")

            // 通知 UI 跳转登录页
            _authEvents.emit(AuthEvent.ForceLogout(message.message))
        }

        "connected" -> {
            // WebSocket 连接成功
        }

        // ... 其他通知类型
    }
}

@Serializable
data class WebSocketMessage(
    val type: String,
    val message: String? = null,
    val data: String? = null,
    val postId: String? = null,
    val userId: String? = null
)
```

### 4.3 WebSocket 重连策略

```kotlin
class WebSocketManager(
    private val tokenStore: TokenStore
) {
    private var retryCount = 0
    private val maxRetries = 5
    private val baseDelay = 1000L  // 1秒

    fun connect() {
        val token = tokenStore.getAccessToken() ?: return

        val request = Request.Builder()
            .url("ws://$HOST/v1/notifications/ws")
            .header("Authorization", "Bearer $token")
            .build()

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryCount = 0  // 重置重试计数
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401) {
                    // JWT 过期，先刷新 token 再重连
                    refreshAndReconnect()
                } else {
                    // 网络错误，指数退避重连
                    scheduleReconnect()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
        })
    }

    private fun scheduleReconnect() {
        if (retryCount >= maxRetries) return

        val delay = baseDelay * (1 shl retryCount)  // 指数退避: 1s, 2s, 4s, 8s, 16s
        retryCount++

        scope.launch {
            delay(delay)
            connect()
        }
    }

    private fun refreshAndReconnect() {
        scope.launch {
            try {
                val refreshToken = tokenStore.getRefreshToken() ?: return@launch
                val result = authApi.refresh(RefreshRequest(refreshToken))
                tokenStore.save(result.token, result.refreshToken, result.expiresIn)
                connect()  // 用新 token 重连
            } catch (e: Exception) {
                tokenStore.clear()
                _authEvents.emit(AuthEvent.ForceLogout("会话已过期"))
            }
        }
    }
}
```

---

## 5. 主动刷新策略（可选优化）

除了被动拦截 401，客户端可以在 JWT 快过期时主动刷新，避免用户感知到 401 延迟：

```kotlin
class TokenRefreshScheduler(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi
) {
    private var refreshJob: Job? = null

    /**
     * 在 JWT 80% 生命周期时主动刷新
     *
     * 对于 3 分钟 JWT：80% = 2:24，留 ~36 秒 + 15 秒服务端 leeway 缓冲
     */
    fun scheduleRefresh() {
        refreshJob?.cancel()

        val expiresIn = tokenStore.getExpiresIn() ?: return
        val obtainedAt = tokenStore.getObtainedAt() ?: return
        val now = System.currentTimeMillis()

        // 在 80% 生命周期时刷新（3分钟 JWT → 2:24 时刷新）
        val refreshAt = obtainedAt + (expiresIn * 0.8).toLong()

        val delayMs = (refreshAt - now).coerceAtLeast(0)

        refreshJob = scope.launch {
            delay(delayMs)
            performRefresh()
        }
    }

    private suspend fun performRefresh() {
        val refreshToken = tokenStore.getRefreshToken() ?: return
        try {
            val result = authApi.refresh(RefreshRequest(refreshToken))
            tokenStore.save(result.token, result.refreshToken, result.expiresIn)
            scheduleRefresh()  // 安排下次刷新
        } catch (e: Exception) {
            // 刷新失败，等 401 拦截器处理
        }
    }
}
```

---

## 6. 登出流程

```kotlin
suspend fun logout() {
    // 1. 清除本地 token
    tokenStore.clear()

    // 2. 关闭 WebSocket
    webSocketManager.disconnect()

    // 3. （可选）通知服务端撤销 refresh token
    // 目前服务端没有 /v1/auth/logout 端点
    // token 会在过期后自动清理
    // 如果需要立即撤销，可以后续添加 logout 端点

    // 4. 跳转登录页
    navigator.navigateToLogin()
}
```

---

## 7. 错误处理决策树

```
收到 HTTP 响应
  │
  ├── 200~299 → 正常处理
  │
  ├── 401 → 检查 error code
  │   ├── INVALID_TOKEN → 尝试 refresh
  │   │   ├── refresh 成功 → 重试原请求
  │   │   └── refresh 失败 → 跳转登录
  │   │
  │   ├── REFRESH_TOKEN_EXPIRED → 跳转登录（token太旧了）
  │   ├── REFRESH_TOKEN_INVALID → 跳转登录（token无效）
  │   ├── TOKEN_REUSE_DETECTED → 跳转登录 + 安全提示
  │   ├── SESSION_REVOKED → 跳转登录（密码已改）
  │   └── AUTH_FAILED → 显示"邮箱或密码错误"（仅登录页）
  │
  ├── 400 → 显示具体错误信息（INVALID_EMAIL等）
  │
  ├── 409 → 检查 error code
  │   ├── USER_EXISTS → 显示"邮箱已注册"
  │   └── STALE_REFRESH_TOKEN → 重读本地最新 token + 重试原请求
  │
  └── 500 → 显示"服务器错误，请稍后重试"

收到 WebSocket 消息
  │
  ├── type: "auth_revoked" → 清除token + 跳转登录 + 显示message
  ├── type: "connected" → 连接成功
  └── type: 其他 → 正常通知处理
```

---

## 8. 测试检查清单

### 8.1 基础流程

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 1 | 注册 → 获取 token | 响应包含 token + refreshToken + expiresIn |
| 2 | 登录 → 获取 token | 同上 |
| 3 | 用 JWT 访问普通接口 | 200 成功 |
| 4 | 用过期 JWT 访问接口 | 401 INVALID_TOKEN |
| 5 | 用 refreshToken 刷新 | 200，获得新 token + 新 refreshToken |
| 6 | 用刷新后的新 JWT 访问接口 | 200 成功 |
| 7 | 用旧 refreshToken 再次刷新 | 10s内：200（宽限期），10s后：401 TOKEN_REUSE_DETECTED |

### 8.2 安全场景

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 8 | 用过期 refreshToken 刷新 | 401 REFRESH_TOKEN_EXPIRED |
| 9 | 用随机字符串刷新 | 401 REFRESH_TOKEN_INVALID |
| 10 | 修改密码后访问敏感路由 | 401 SESSION_REVOKED |
| 11 | 修改密码后访问普通路由 | 200（JWT未过期期间仍可用） |
| 12 | 修改密码后 WebSocket 收到消息 | 收到 auth_revoked |

### 8.3 并发场景

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 13 | 两个 tab 同时 refresh（间隔<10s） | 第一个成功，第二个返回 409 STALE_REFRESH_TOKEN |
| 14 | 两个 tab 同时 refresh（间隔>10s） | 第二个失败 + family撤销 |
| 15 | 收到 STALE_REFRESH_TOKEN 后重读本地 token 重试 | 使用最新 token 请求成功 |
