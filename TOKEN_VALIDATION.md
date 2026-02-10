# Token 自动验证与登出功能

## 功能概述

实现了完整的JWT token自动验证机制：
- **服务端**：提供 `/v1/auth/validate` 端点验证token有效性
- **客户端**：自动监听token变化，验证有效性，无效时自动清除（触发登出）

## 架构设计

### 服务端 (src/main/kotlin/features/auth/AuthRoutes.kt)

新增验证端点：
```kotlin
GET /v1/auth/validate
Authorization: Bearer <token>

成功响应 (200):
{
  "valid": true,
  "userId": "user-id"
}

失败响应 (401):
{
  "code": "INVALID_TOKEN",
  "message": "Token is invalid or has expired"
}
```

**实现要点**：
- 使用 `authenticate("auth-jwt")` 保护端点
- JWT验证在Ktor中间件层完成（plugins/Security.kt）
- 验证失败自动返回401（通过 `challenge` 配置）

### 客户端 (AuthRepositoryImpl)

**响应式架构**：
```
TokenDataSource.token (Flow<AuthToken?>)
    ↓ distinctUntilChanged()
    ↓ 每次token变化时触发
    ↓
    ├─ token == null → UserSession.Unauthenticated
    │
    └─ token != null
        ↓ validateToken(token) 调用服务端
        ├─ valid → UserSession.Authenticated(token)
        └─ invalid → clearToken() + UserSession.Unauthenticated
```

**关键特性**：
1. **自动触发**：token从DataStore变化时自动验证
2. **无需手动调用**：MainViewModel只需 `collectAsState(session)`
3. **自动登出**：验证失败时自动清除token，触发导航到登录页

## 使用示例

### 测试Token过期场景

1. 修改TokenService使token立即过期：
```kotlin
// TokenService.kt
.withExpiresAt(Date(System.currentTimeMillis() - 1000)) // 已过期
```

2. 登录后，应用会自动：
   - 检测到token变化
   - 调用 `/v1/auth/validate`
   - 收到401响应
   - 清除本地token
   - 导航回登录页

### 手动测试验证端点

```bash
# 获取token（登录后）
TOKEN="your-jwt-token-here"

# 验证有效token
curl -X GET http://localhost:8080/v1/auth/validate \
  -H "Authorization: Bearer $TOKEN"

# 验证无效token
curl -X GET http://localhost:8080/v1/auth/validate \
  -H "Authorization: Bearer invalid-token"
```

## 实现细节

### 服务端JWT配置 (plugins/Security.kt)

```kotlin
jwt("auth-jwt") {
    validate { credential ->
        val userId = credential.payload.getClaim("id").asString()
        if (!userId.isNullOrBlank()) {
            UserPrincipal(userId)
        } else {
            null
        }
    }
    challenge { _, _ ->
        call.respond(
            HttpStatusCode.Unauthorized,
            mapOf(
                "code" to "INVALID_TOKEN",
                "message" to "Token is invalid or has expired"
            )
        )
    }
}
```

**验证流程**：
1. 提取 `Authorization: Bearer <token>` header
2. 验证JWT签名、过期时间、issuer、audience
3. 调用 `validate` 块提取userId
4. 失败时调用 `challenge` 返回自定义401响应

### 客户端自动验证 (AuthRepositoryImpl)

```kotlin
init {
    repositoryScope.launch {
        tokenDataSource.token
            .distinctUntilChanged()
            .collect { token ->
                if (token != null) {
                    val isValid = validateToken(token).getOrElse { false }
                    if (isValid) {
                        _sessionState.value = UserSession.Authenticated(token)
                    } else {
                        tokenDataSource.clearToken()
                        _sessionState.value = UserSession.Unauthenticated
                    }
                } else {
                    _sessionState.value = UserSession.Unauthenticated
                }
            }
    }
}
```

**关键点**：
- `repositoryScope`：独立的CoroutineScope，生命周期与Repository绑定
- `distinctUntilChanged()`：防止重复验证相同token
- 验证失败时自动 `clearToken()`，触发下一轮collect（token变为null）

## 错误处理

### 网络错误
- 服务端不可达：`getOrElse { false }` 返回false，视为无效
- 防止网络抖动导致误登出

### Token格式错误
- JWT解析失败：Ktor返回401
- 客户端视为无效token

### 并发安全
- `MutableStateFlow` 保证线程安全
- `SupervisorJob` 防止子协程错误影响整个scope

## 与MainViewModel集成

```kotlin
// MainViewModel.kt
val session by authRepository.session.collectAsState(initial = null)

LaunchedEffect(session) {
    val route = when (session) {
        is UserSession.Authenticated -> NavigationRoute.Home
        UserSession.Unauthenticated -> NavigationRoute.Login
        null -> NavigationRoute.Splash
    }
    // 自动导航
}
```

**流程**：
1. 应用启动 → session初始为null → 显示Splash
2. Repository验证token →
   - 有效：session变为Authenticated → 导航Home
   - 无效/不存在：session变为Unauthenticated → 导航Login
3. Token过期后 → Repository自动验证失败 → session变为Unauthenticated → 自动返回Login

## 性能考虑

- **初始验证**：应用启动时验证一次
- **Token变化验证**：登录/注册时验证新token
- **不重复验证**：使用 `distinctUntilChanged()` 避免重复请求

## 未来优化

1. **Token刷新机制**：在过期前自动刷新token
2. **离线缓存**：记录最后验证时间，短期内离线不触发登出
3. **验证节流**：防止频繁网络请求
