# 媒体功能实现总结

## 实现概览

根据 Hexagonal Architecture 和 Domain-Driven Design 原则，完成了媒体上传功能的完整实现。整个系统分为 Domain、UseCase、Data、Feature 四层，通过依赖倒置实现高度的可扩展性。

---

## 1. Domain 层（业务规则）

### 文件结构

```
domain/
├── failure/MediaErrors.kt           # 错误类型定义
├── model/Media.kt                   # Domain models
└── repository/MediaStorageRepository.kt  # 存储接口
```

### 详细设计

#### MediaErrors.kt

**8 种错误类型的密封接口**：

| 错误类型 | HTTP 状态 | 说明 |
|---------|---------|------|
| `InvalidFileType(received, allowed)` | 400 | 不支持的 MIME 类型 |
| `FileTooLarge(size, maxSize)` | 400 | 文件大小超过限制 |
| `InvalidMediaUrl(url)` | 400 | 媒体 URL 格式不合法 |
| `InvalidFileName(fileName)` | 400 | 文件名包含非法字符 |
| `UploadFailed(reason)` | 500 | 上传失败 |
| `DeleteFailed(reason)` | 500 | 删除失败 |
| `StorageError(message)` | 500 | 存储系统错误 |
| `UnsupportedOperation` | 501 | 操作不支持 |

**特点**：
- 密封接口确保编译时穷尽性检查
- 错误包含上下文信息（如实际大小、允许的类型）
- 可在 Feature 层精确映射到 HTTP 状态码

#### Media.kt

**三个核心 Domain Models**：

```kotlin
@JvmInline
value class MediaId(val value: String)

data class MediaConfig(
    val uploadDir: String,
    val maxFileSize: Long,
    val allowedTypes: Set<String>,
    val enableDatabase: Boolean = false
)

data class UploadedMedia(
    val id: MediaId,
    val url: MediaUrl,  // 复用 Post 中的 MediaUrl
    val type: MediaType,
    val fileSize: Long,
    val contentType: String,
    val uploadedAt: Long
)
```

**设计特点**：
- `MediaId` 使用 inline value class，防止 ID 混淆
- `MediaConfig` 聚合所有配置参数，从 YAML 读取
- `UploadedMedia` 与 Post 功能共用 `MediaUrl` 和 `MediaType`

#### MediaStorageRepository.kt

**存储操作的 Port 接口**：

```kotlin
interface MediaStorageRepository {
    suspend fun upload(file: ByteArray, fileName: String): Either<MediaError, UploadedMedia>
    suspend fun delete(mediaId: MediaId): Either<MediaError, Unit>
}
```

**设计原则**：
- 使用 `Either<MediaError, T>` 处理可预期的错误
- `suspend` 关键字支持协程（IO 密集操作）
- 接口不提供实现细节，支持多种存储方案

---

## 2. UseCase 层（业务逻辑）

### UploadMediaUseCase.kt

**职责**：验证 + 编排 + 返回结果

```kotlin
class UploadMediaUseCase(
    private val storageRepository: MediaStorageRepository,
    private val config: MediaConfig
) {
    suspend operator fun invoke(command: UploadMediaCommand): Either<MediaError, UploadedMedia>
}
```

**处理流程**：

1. **验证 MIME 类型**
   ```kotlin
   if (command.contentType !in config.allowedTypes) {
       raise(MediaError.InvalidFileType(command.contentType, config.allowedTypes))
   }
   ```
   - 白名单检查，防止执行文件上传
   - 错误信息包含支持的类型列表

2. **验证文件大小**
   ```kotlin
   val fileSize = command.fileBytes.size.toLong()
   if (fileSize > config.maxFileSize) {
       raise(MediaError.FileTooLarge(fileSize, config.maxFileSize))
   }
   ```
   - 内存中检查，快速失败
   - 错误信息包含限制大小

3. **生成安全文件名（基于内容哈希）**
   ```kotlin
   val safeFileName = generateSafeFileName(fileBytes, contentType)
   // 计算 MD5 哈希 + 扩展名
   // 结果：{MD5哈希}.{extension}  如：5d41402abc4b2a76b9719d911017c592.jpg
   ```
   - 使用文件内容的 MD5 哈希防止重复上传
   - 同样内容重复上传会自动识别（去重）
   - 从 MIME 类型确定正确扩展名
   - 文件名格式固定，安全性高

   **优势**：
   - ✓ **防重复**：同一文件重复上传只存储一次
   - ✓ **节省空间**：避免重复的文件副本
   - ✓ **简化验证**：文件名格式固定（32位hex + 扩展名）
   - ✓ **唯一性**：MD5 冲突概率极低

4. **调用 Repository**
   ```kotlin
   storageRepository.upload(fileBytes, safeFileName).bind()
   ```
   - 将文件操作委托给实现层
   - Repository 会自动处理去重（文件已存在则返回已有信息）
   - `bind()` 在 Either 的 success 路径继续

**Error Propagation**：
```kotlin
// Arrow 的 either {} DSL 自动处理错误链：
// 任何 raise() 或 bind() 失败都会短路到 Either.Left
```

---

## 3. Data 层（存储实现）

### FileSystemMediaStorageRepository.kt

**将媒体文件存储到本地磁盘**

#### 初始化

```kotlin
class FileSystemMediaStorageRepository(uploadDir: String) : MediaStorageRepository {
    init {
        val directory = File(uploadDir)
        if (!directory.exists()) {
            directory.mkdirs()  // 启动时确保目录存在
        }
    }
}
```

#### upload() 实现

**职责**：验证 → 写入（支持去重）→ 返回信息

```
输入 ByteArray + fileName (格式：{MD5哈希}.{扩展名})
  ↓
1. 验证文件名安全性
   - 格式：32位MD5十六进制 + 点 + 扩展名 (2-5位)
   - 示例：5d41402abc4b2a76b9719d911017c592.jpg
   - 防止路径遍历攻击
  ↓
2. 检查文件是否已存在
   - 若存在 → 返回已有文件信息（去重）
   - 若不存在 → 继续写入
  ↓
3. 写入新文件到磁盘
   - filePath = uploadDir + fileName
   - file.writeBytes(bytes)
  ↓
4. 生成公开 URL
   - /uploads/{fileName}
   - 用户可通过 GET 请求直接访问
  ↓
5. 推断媒体类型
   - 从扩展名推断 (IMAGE 或 VIDEO)
  ↓
输出 Either<MediaError, UploadedMedia>
```

**安全性考虑**：

| 威胁 | 防御措施 |
|------|---------|
| 路径遍历攻击 | 验证文件名，不含 "/" 或 ".." |
| 文件名冲突 | 使用 UUID，概率极低 |
| 恶意扩展名 | 不信任客户端上传的扩展名 |
| 覆盖现有文件 | UUID 保证唯一性 |

**日志记录**：
```kotlin
logger.info("Media uploaded: fileName=..., size=..., type=..., duration=...ms")
logger.error("Failed to upload media", exception)
```

#### delete() 实现

**职责**：验证 → 删除文件

```kotlin
// 1. 验证 mediaId (文件名)
// 2. 检查文件是否存在
// 3. 验证是否真的是文件 (不是目录)
// 4. 删除文件
// 5. 返回 Either<MediaError, Unit>
```

---

## 4. Feature 层（HTTP 适配）

### MediaRoutes.kt (重构)

**从直接文件操作 → 注入 UseCase**

#### 之前（硬耦合）
```kotlin
fun Route.mediaRoutes(uploadDir: String) {
    // 验证逻辑直接在路由中
    // 硬编码 MAX_FILE_SIZE、ALLOWED_TYPES
    // 直接调用 File.writeBytes()
}
```

#### 现在（依赖倒置）
```kotlin
fun Route.mediaRoutes(uploadMediaUseCase: UploadMediaUseCase) {
    route("/v1/media") {
        authenticate("auth-jwt") {
            post("/upload") {
                // 1. 接收 multipart 请求
                // 2. 遍历 parts，处理第一个文件
                // 3. 创建命令
                // 4. 调用 UseCase
                // 5. 映射结果并响应（成功 or 错误）
                // 6. 处理完后立即返回（单文件模式）
            }
        }
    }
}
```

**关键改进**：
- ✓ 业务逻辑完全独立
- ✓ 协议转换专一
- ✓ 支持单元测试
- ✓ 正确的循环遍历逻辑（part 变量正确推进）
- ✓ 单次响应策略（处理完立即返回，避免 HTTP 异常）

**Multipart 处理流程**：
```
接收 multipart 请求
  ↓
var part = multipart.readPart()
  ↓
while (part != null) {
  - 如果是 FileItem：处理并响应，return
  - 否则：跳过该 part
  - part = multipart.readPart()  // 正确推进
}
  ↓
如果没有文件：返回 400 错误
```

**为什么单文件模式**：
- 避免一次请求内多次 `call.respond()`
- HTTP 协议不允许多次响应
- 如果将来需要多文件，返回统一的 JSON 数组即可

### MediaMappers.kt

**Domain ↔ HTTP 转换**

#### Error 映射

```kotlin
fun MediaError.toHttpResponse(): Pair<HttpStatusCode, ErrorResponse> = when (this) {
    is MediaError.InvalidFileType ->
        HttpStatusCode.BadRequest to ErrorResponse(
            error = "Unsupported file type",
            details = "Received: ${this.received}. Allowed: ${this.allowed.joinToString(", ")}"
        )

    is MediaError.FileTooLarge ->
        HttpStatusCode.BadRequest to ErrorResponse(
            error = "File too large",
            details = "Received: ${formatBytes(this.size)}, Max: ${formatBytes(this.maxSize)}"
        )

    // ... 其他类型

    is MediaError.UploadFailed ->
        HttpStatusCode.InternalServerError to ErrorResponse(
            error = "Upload failed",
            details = this.reason
        )
}
```

#### Response 映射

```kotlin
fun UploadedMedia.toResponse(): MediaUploadResponse = MediaUploadResponse(
    url = this.url.value,      // "/uploads/{uuid}.jpg"
    type = this.type.name      // "IMAGE" 或 "VIDEO"
)
```

### MediaSchema.kt

**数据传输对象**：

```kotlin
@Serializable
data class MediaUploadResponse(
    val url: String,           // "/uploads/{uuid}.jpg"
    val type: String           // "IMAGE" 或 "VIDEO"
)
```

---

## 5. DI 配置（Koin）

### MediaModule.kt

**集中管理媒体相关的 beans**

```kotlin
fun mediaModule(app: Application) = module {
    // 1. 从 YAML 读取配置，创建 MediaConfig
    single {
        MediaConfig(
            uploadDir = config.property("media.uploadDir").getString() ?: "uploads",
            maxFileSize = config.property("media.maxFileSize").getLong() ?: 10MB,
            allowedTypes = config.property("media.allowedTypes").getSet(),
            enableDatabase = config.property("media.enableDatabase").getBoolean()
        )
    }

    // 2. 注册 Repository 实现
    single<MediaStorageRepository> {
        FileSystemMediaStorageRepository(get<MediaConfig>().uploadDir)
    }

    // 3. 注册 UseCase
    single { UploadMediaUseCase(get(), get()) }
}
```

**优势**：
- 配置集中，易于调整
- 依赖自动注入，减少手动 wiring
- 支持在运行时切换实现（开发/生产）

### Frameworks.kt

**注册 MediaModule**

```kotlin
fun Application.configureFrameworks(tokenConfig: TokenConfig) {
    install(Koin) {
        modules(dataModule, domainModule, mediaModule(this@configureFrameworks), securityModule(tokenConfig))
    }
}
```

---

## 6. 配置管理

### application.yaml

```yaml
media:
  uploadDir: "uploads"                                    # 上传目录
  maxFileSize: 10485760                                  # 10MB in bytes
  allowedTypes: "image/jpeg,image/png,image/webp,video/mp4"
  enableDatabase: false                                  # 保留未来扩展
```

**特点**：
- 环境隔离：不同环境用不同配置文件
- 运行时读取：无需重新编译
- 敏感值可用环境变量覆盖

---

## 7. 路由配置

### Routing.kt 变更

#### 之前
```kotlin
val uploadDir = environment.config.propertyOrNull("media.uploadDir")?.getString() ?: "uploads"
mediaRoutes(uploadDir)
```

#### 现在
```kotlin
val uploadMediaUseCase by inject<UploadMediaUseCase>()
mediaRoutes(uploadMediaUseCase)
```

**改进**：
- 从注入 UseCase 而非路径
- 配置集中在 DI 层
- 路由不关心配置细节

---

## 8. API 使用示例

### 上传文件

```bash
curl -X POST http://localhost:8080/v1/media/upload \
  -H "Authorization: Bearer {token}" \
  -F "file=@/path/to/image.jpg"

# 响应 201 Created
{
  "url": "/uploads/abc123-def456.jpg",
  "type": "IMAGE"
}
```

### 访问上传的文件

```bash
curl http://localhost:8080/uploads/abc123-def456.jpg
# 浏览器直接访问
```

### 错误响应示例

```bash
# 文件类型不支持 (400 Bad Request)
{
  "error": "Unsupported file type",
  "details": "Received: application/exe. Allowed: image/jpeg, image/png, ..."
}

# 文件太大 (400 Bad Request)
{
  "error": "File too large",
  "details": "Received: 50 MB, Max: 10 MB"
}

# 服务器错误 (500 Internal Server Error)
{
  "error": "Upload failed",
  "details": "Disk space insufficient"
}
```

---

## 9. 架构原则验证

### ✅ Hexagonal Architecture

- **Domain 层**：纯 Kotlin，无框架依赖
  - MediaError, MediaId, MediaConfig 等 models
  - MediaStorageRepository interface (Port)
  - UploadMediaUseCase 编排业务逻辑

- **Infrastructure 层**：实现 Domain 接口
  - FileSystemMediaStorageRepository (Adapter)
  - 文件系统操作、安全验证

- **Feature 层**：协议转换
  - MediaRoutes (HTTP Adapter)
  - MediaMappers (DTO conversion)

### ✅ 依赖倒置原则

```
MediaRoutes (depends on)
    ↓
UploadMediaUseCase (uses)
    ↓
MediaStorageRepository Interface (Domain)
    ↑ (implemented by)
FileSystemMediaStorageRepository (Data)
```

**验证**：Domain 层不导入任何框架代码 ✓

### ✅ 错误处理

- 使用 `Either<MediaError, T>` 替代异常
- MediaError 密封接口，编译时穷尽检查
- Feature 层精确映射到 HTTP 状态码

### ✅ 类型安全

- `MediaId` 为 inline value class
- `MediaUrl` 复用自 Post 功能
- 编译器防止 ID 混淆

---

## 10. 文件清单

### 新增文件

```
src/main/kotlin/
├── domain/failure/MediaErrors.kt
├── domain/model/Media.kt
├── domain/repository/MediaStorageRepository.kt
├── domain/usecase/UploadMediaUseCase.kt
├── data/repository/FileSystemMediaStorageRepository.kt
├── features/media/MediaMappers.kt
└── core/di/MediaModule.kt
```

### 修改文件

```
src/main/kotlin/
├── Frameworks.kt (添加 mediaModule)
├── features/media/MediaRoutes.kt (重构为注入 UseCase)
├── features/media/MediaSchema.kt (添加注释)
└── plugins/Routing.kt (更改参数注入)

src/main/resources/
└── application.yaml (添加 media 配置)
```

---

## 11. 未来扩展

### S3 存储

```kotlin
class S3MediaStorageRepository(
    private val s3Client: S3Client,
    private val bucketName: String
) : MediaStorageRepository {
    override suspend fun upload(...): Either<MediaError, UploadedMedia> {
        // S3 上传逻辑
        // 返回 S3 URL 或预签名 URL
    }
}

// DI 中切换
single<MediaStorageRepository> {
    when (config.storageType) {
        "s3" -> S3MediaStorageRepository(...)
        else -> FileSystemMediaStorageRepository(...)
    }
}
```

### 数据库元数据

```kotlin
class DatabaseMediaStorageRepository(
    private val fileSystem: FileSystemMediaStorageRepository,
    private val metadata: MediaMetadataRepository
) : MediaStorageRepository {
    // 在 upload 时同时记录元数据
}
```

### 异步处理

```kotlin
// 支持大文件上传进度推送
// 媒体处理完成后通过 WebSocket 通知
```

---

## 12. 关键改进总结

| 方面 | 之前 | 现在 |
|------|------|------|
| **业务逻辑** | 硬耦合路由 | 独立 UseCase |
| **错误处理** | 字符串消息 | `Either<MediaError, T>` |
| **存储实现** | 直接文件系统 | `MediaStorageRepository` 接口 |
| **依赖注入** | 手动注入路径 | Koin 自动管理 |
| **配置** | 硬编码常量 | YAML 配置读取 |
| **验证** | 分散在多处 | UseCase 集中 |
| **可测试性** | 困难（集成测试） | 易于单元测试 |
| **扩展性** | 困难（修改路由） | 易于实现新 adapter |

---

**实现完成！** ✅

整个媒体功能已遵循六边形架构的所有原则，核心业务逻辑完全独立，支持从文件系统轻松扩展到 S3/OSS 等云存储方案。
