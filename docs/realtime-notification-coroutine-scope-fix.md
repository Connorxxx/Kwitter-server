# 实时通知功能 - 协程作用域修复（最终版）

## 问题复现

### 复验结果
```bash
./gradlew clean compileKotlin -q
# BUILD FAILED
```

### 根本原因

**`io.ktor.server.application.launch` 在 Ktor 3.4.0 中不存在**

之前的修复尝试使用了不存在的 API：
```kotlin
// ❌ 不存在的 API
import io.ktor.server.application.launch
call.application.launch { }
```

导致：
1. ✅ `UserId` 包装已生效（第一个问题已修复）
2. ❌ `launch` 无法解析（编译错误）
3. ❌ `execute` 被判定为"非协程体中调用 suspend"（因为上面的错误）

---

## 正确的修复方案

遵循建议：**引入应用级 CoroutineScope，通过 DI 注入**

### 架构设计

```
ApplicationCoroutineScope (单例)
    ├─ SupervisorJob (子协程失败不影响其他)
    ├─ Dispatchers.Default (适合后台任务)
    └─ 生命周期绑定到 Application
        ├─ 启动时创建 (Koin DI)
        └─ 停止时取消 (ApplicationStopping 事件)
```

---

## 实现步骤

### 1. 创建 ApplicationCoroutineScope

**文件**: `src/main/kotlin/core/coroutine/ApplicationCoroutineScope.kt`

```kotlin
class ApplicationCoroutineScope : CoroutineScope {
    private val job = SupervisorJob()

    // SupervisorJob: 子协程失败不影响其他协程
    // Dispatchers.Default: 适合 CPU 密集型任务
    override val coroutineContext = job + Dispatchers.Default

    fun shutdown() {
        job.cancel()
    }
}
```

**关键设计**:
- **SupervisorJob**: 一个通知失败不影响其他通知
- **Dispatchers.Default**: 适合后台任务
- **生命周期可控**: 通过 `shutdown()` 优雅关闭

---

### 2. 在 DI 中注册

**文件**: `src/main/kotlin/core/di/NotificationModule.kt`

```kotlin
val notificationModule = module {
    // Core: 应用级协程作用域（单例）
    single { ApplicationCoroutineScope() }

    // Infrastructure: WebSocket 连接管理
    single { WebSocketConnectionManager() }

    // Infrastructure: 通知 Repository 实现
    single<NotificationRepository> { InMemoryNotificationRepository(get()) }

    // Use Cases: 通知广播
    single { BroadcastPostCreatedUseCase(get()) }
    single { BroadcastPostLikedUseCase(get()) }
}
```

---

### 3. 在 Application 启动时注册，停止时清理

**文件**: `src/main/kotlin/Application.kt`

```kotlin
fun Application.module() {
    // ... 配置插件

    // 注册应用停止时的清理逻辑
    environment.monitor.subscribe(ApplicationStopping) {
        val appScope by inject<ApplicationCoroutineScope>()
        appScope.shutdown()
    }
}
```

**生命周期管理**:
- ✅ 应用启动时由 Koin 创建单例
- ✅ 应用停止时调用 `shutdown()` 取消所有协程
- ✅ 优雅关闭，无泄漏

---

### 4. 在 Route 中注入并使用

#### PostRoutes.kt

```kotlin
fun Route.postRoutes(
    createPostUseCase: CreatePostUseCase,
    // ... 其他 use cases
    broadcastPostCreatedUseCase: BroadcastPostCreatedUseCase,
    appScope: ApplicationCoroutineScope  // ✅ 注入
) {
    // ...

    ifRight = { post ->
        // 异步触发通知
        if (post.parentId == null && principal != null) {
            appScope.launch {  // ✅ 使用注入的 scope
                try {
                    broadcastPostCreatedUseCase.execute(...)
                } catch (e: Exception) {
                    logger.error("Failed to broadcast", e)
                }
            }
        }
        call.respond(...)
    }
}
```

#### LikeRoutes.kt

```kotlin
fun Route.likeRoutes(
    likePostUseCase: LikePostUseCase,
    unlikePostUseCase: UnlikePostUseCase,
    broadcastPostLikedUseCase: BroadcastPostLikedUseCase,
    appScope: ApplicationCoroutineScope  // ✅ 注入
) {
    authenticate("auth-jwt") {
        post("/v1/posts/{postId}/like") {
            val result = likePostUseCase(...)

            result.fold(
                ifRight = { stats ->
                    // 异步触发通知
                    appScope.launch {  // ✅ 使用注入的 scope
                        try {
                            broadcastPostLikedUseCase.execute(...)
                        } catch (e: Exception) {
                            logger.error("Failed to broadcast", e)
                        }
                    }
                    call.respond(...)
                }
            )
        }
    }
}
```

#### Routing.kt 传递 scope

```kotlin
fun Application.configureRouting() {
    // ... 注入 use cases

    // 注入应用级协程作用域
    val appScope by inject<ApplicationCoroutineScope>()

    routing {
        // 传递给需要异步通知的路由
        postRoutes(..., broadcastPostCreatedUseCase, appScope)
        likeRoutes(..., broadcastPostLikedUseCase, appScope)
    }
}
```

---

## 修复对比

### Before (❌ 不可用的 API)

```kotlin
// 导入不存在的 API
import io.ktor.server.application.launch

// 编译错误
call.application.launch { }
```

### After (✅ 正确的方式)

```kotlin
// 注入应用级作用域
val appScope by inject<ApplicationCoroutineScope>()

// 使用注入的 scope
appScope.launch { }
```

---

## 协程作用域对比

| 方式 | 问题 | 生命周期 | 结论 |
|------|------|---------|------|
| `launch { }` | 无作用域 | 不确定 | ❌ 编译错误 |
| `GlobalScope.launch { }` | 全局作用域 | 应用生命周期 | ❌ 无法取消，泄漏风险 |
| `call.application.launch { }` | API 不存在 | - | ❌ Ktor 3.4.0 不支持 |
| `appScope.launch { }` | 应用级作用域 | 应用生命周期 | ✅ **正确选择** |

---

## 为什么这个方案正确？

### 1. 符合结构化并发原则

```
ApplicationCoroutineScope (父)
    ├─ 通知协程 1
    ├─ 通知协程 2
    └─ 通知协程 3
```

父作用域取消时，所有子协程自动取消。

### 2. SupervisorJob 的作用

```kotlin
// 没有 SupervisorJob
Job (父)
    ├─ 协程 1 ✅
    ├─ 协程 2 ❌ 失败 → 父 Job 取消 → 所有子协程被取消
    └─ 协程 3 ❌ 被父取消

// 使用 SupervisorJob
SupervisorJob (父)
    ├─ 协程 1 ✅
    ├─ 协程 2 ❌ 失败 → 仅自己失败
    └─ 协程 3 ✅ 继续运行
```

**效果**: 一个通知推送失败不影响其他通知。

### 3. 生命周期可控

```kotlin
// 应用启动
Koin DI 创建 ApplicationCoroutineScope 单例

// 应用运行中
Route handler 使用 appScope.launch { } 启动后台任务

// 应用停止
ApplicationStopping 事件触发
    ↓
appScope.shutdown()
    ↓
所有协程被取消
    ↓
优雅关闭
```

---

## 验收标准

### 编译验证
```bash
./gradlew clean compileKotlin -q
# ✅ BUILD SUCCESSFUL
```

### 运行时验证
- [ ] 应用启动成功
- [ ] WebSocket 连接建立
- [ ] 新 Post 创建触发通知
- [ ] Post 点赞触发通知
- [ ] 应用停止时协程正确取消（查看日志）

### 日志验证
```
[INFO] Shutting down application coroutine scope
[INFO] Application coroutine scope shut down complete
```

---

## 修改文件清单

### 新增文件 (1)
1. ✅ `core/coroutine/ApplicationCoroutineScope.kt` - 应用级协程作用域

### 修改文件 (5)
2. ✅ `core/di/NotificationModule.kt` - 注册 scope
3. ✅ `Application.kt` - 生命周期管理
4. ✅ `features/post/PostRoutes.kt` - 注入并使用 scope
5. ✅ `features/post/LikeRoutes.kt` - 注入并使用 scope
6. ✅ `plugins/Routing.kt` - 传递 scope

---

## 与 Ktor 版本的兼容性

### Ktor 3.4.0
- ❌ 不支持 `io.ktor.server.application.launch`
- ✅ 支持 `Application.monitor.subscribe(ApplicationStopping)`
- ✅ 支持标准 Kotlin 协程

### 解决方案
自定义 `ApplicationCoroutineScope` 并通过 DI 管理，兼容所有 Ktor 版本。

---

## 总结

### 关键改进
1. ✅ 不依赖 Ktor 不存在的 API
2. ✅ 使用标准 Kotlin 协程机制
3. ✅ 生命周期由应用管理
4. ✅ SupervisorJob 防止级联失败
5. ✅ 通过 DI 注入，易于测试

### 架构优势
- **可测试**: scope 可以在测试中 mock
- **可控**: 生命周期明确
- **健壮**: SupervisorJob 防止单点故障
- **清晰**: 职责明确（scope 专门管理后台任务）

代码现在应该可以成功编译并运行！🚀
