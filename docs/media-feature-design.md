# 媒体功能设计文档

## 架构概览

遵循 Hexagonal Architecture (Ports & Adapters) 和 DDD 原则：

```
┌─────────────────────────────────────────────────────────────┐
│                        Domain Layer                         │
│  (纯 Kotlin，无框架依赖，业务规则的唯一真相来源)               │
├─────────────────────────────────────────────────────────────┤
│  Models:                                                    │
│    - MediaId (inline value class)                           │
│    - MediaConfig (配置聚合)                                 │
│    - UploadedMedia (上传结果)                               │
│                                                             │
│  Errors (sealed interface):                                │
│    - MediaError (InvalidFileType, FileTooLarge, etc.)      │
│                                                             │
│  Repository (Port/Interface):                              │
│    - MediaStorageRepository (定义契约，实现在底层)          │
│                                                             │
│  Use Cases (Application Services):                         │
│    - UploadMediaUseCase                                    │
└─────────────────────────────────────────────────────────────┘
                              ↓
                    (依赖倒置：接口在上，实现在下)
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   Infrastructure Layer                      │
│              (存储实现、外部服务的具体实现)                    │
├─────────────────────────────────────────────────────────────┤
│  - FileSystemMediaStorageRepository (文件系统实现)           │
│  - S3MediaStorageRepository (未来实现)                       │
│  - OSSMediaStorageRepository (未来实现)                      │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      Transport Layer                        │
│                   (HTTP API - Ktor Routes)                  │
├─────────────────────────────────────────────────────────────┤
│  - POST   /v1/media/upload      (上传媒体)                  │
│  - MediaMappers                 (请求/响应转换)             │
└─────────────────────────────────────────────────────────────┘
```

---

## Domain Models 设计

### 1. MediaId（媒体标识符）

**类型安全的 ID**：
```kotlin
@JvmInline
value class MediaId(val value: String)
```

使用 inline value class 防止 ID 混淆，编译时即可发现错误。

### 2. MediaConfig（配置聚合）

**集中管理媒体相关配置**：
```kotlin
data class MediaConfig(
    val uploadDir: String,              // 上传目录路径
    val maxFileSize: Long,              // 最大文件大小（字节）
    val allowedTypes: Set<String>,      // MIME 类型白名单
    val enableDatabase: Boolean = false // 是否记录元数据到数据库
)
```

从 application.yaml 读取，支持环境变量覆盖。

### 3. UploadedMedia（上传结果）

**存储操作返回的结果**：
```kotlin
data class UploadedMedia(
    val id: MediaId,                    // 媒体 ID
    val url: MediaUrl,                  // 公开访问 URL
    val type: MediaType,                // 媒体类型（IMAGE/VIDEO）
    val fileSize: Long,                 // 文件大小（字节）
    val contentType: String,            // MIME 类型
    val uploadedAt: Long                // 上传时间戳
)
```

### 4. MediaError（错误类型）

**密封接口，编译时穷尽检查**：
```kotlin
sealed interface MediaError {
    // 验证错误
    data class InvalidFileType(val received: String, val allowed: Set<String>) : MediaError
    data class FileTooLarge(val size: Long, val maxSize: Long) : MediaError
    data class InvalidMediaUrl(val url: String) : MediaError
    data class InvalidFileName(val fileName: String) : MediaError

    // 操作错误
    data class UploadFailed(val reason: String) : MediaError
    data class DeleteFailed(val reason: String) : MediaError
    data class StorageError(val message: String) : MediaError
    data object UnsupportedOperation : MediaError
}
```

---

## Repository 接口设计

### MediaStorageRepository（Port）

**存储操作的抽象接口**：

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `upload(file: ByteArray, fileName: String)` | `Either<MediaError, UploadedMedia>` | 上传文件到存储 |
| `delete(mediaId: MediaId)` | `Either<MediaError, Unit>` | 删除已上传的文件 |

**设计决策**：

**为何使用 `Either<MediaError, T>` 而非抛异常？**
- 错误是业务规则的一部分（文件过大、不支持的类型等都是预期的）
- 编译器强制错误处理（`when` exhaustiveness check）
- Railway-Oriented Programming：清晰的成功/失败路径

**为何分离接口到 Domain 层？**
- 依赖倒置原则：Domain 不依赖具体实现
- 支持多种存储方案的切换（文件系统、S3、OSS）
- 便于单元测试（可以注入 Mock 实现）

---

## Use Cases 设计

### UploadMediaUseCase

**业务规则编排**：
1. 验证 MIME 类型（白名单检查）
2. 验证文件大小（不超过配置限制）
3. 根据文件内容计算 MD5 哈希作为文件名（支持去重和节省空间）
4. 调用 Repository 执行存储
5. 返回 `Either<MediaError, UploadedMedia>` 处理结果或错误

**输入**：
```kotlin
data class UploadMediaCommand(
    val fileName: String,        // 原始文件名
    val contentType: String,     // MIME 类型
    val fileBytes: ByteArray     // 文件内容
)
```

**输出**：
```kotlin
Either<MediaError, UploadedMedia>  // 成功返回上传信息，失败返回错误
```

**错误处理**：
- `InvalidFileType`：不在白名单中的 MIME 类型 → 400 Bad Request
- `FileTooLarge`：文件大小超过限制 → 400 Bad Request
- `InvalidFileName`：文件名包含非法字符 → 400 Bad Request
- `UploadFailed`/`StorageError`：I/O 或存储系统错误 → 500 Internal Server Error

---

## Error Handling 策略

### MediaError 映射到 HTTP

```kotlin
sealed interface MediaError {
    // 验证错误 → 400 Bad Request
    data class InvalidFileType(...) : MediaError
    data class FileTooLarge(...) : MediaError
    data class InvalidMediaUrl(...) : MediaError
    data class InvalidFileName(...) : MediaError

    // 操作错误 → 500 Internal Server Error
    data class UploadFailed(...) : MediaError
    data class DeleteFailed(...) : MediaError
    data class StorageError(...) : MediaError

    // 不支持的操作 → 501 Not Implemented
    data object UnsupportedOperation : MediaError
}
```

在 Feature 层的 Mapper 中完成映射：
```kotlin
fun MediaError.toHttpResponse(): Pair<HttpStatusCode, ErrorResponse>
```

---

## 数据流示例

### 上传媒体的完整流程

```
Client Request (multipart/form-data)
    ↓
MediaRoutes.kt (Transport Layer)
    - 接收 multipart 请求
    - 提取文件名、MIME 类型、内容
    - 构造 UploadMediaCommand
    ↓
UploadMediaUseCase (Application Service)
    - 验证 MIME 类型 (白名单检查)
    - 验证文件大小 (不超过限制)
    - 计算文件 MD5 哈希
    - 生成安全文件名 (MD5 + 扩展名，支持去重)
    - 调用 MediaStorageRepository.upload()
    ↓
FileSystemMediaStorageRepository (Infrastructure)
    - 验证文件名安全性 (防止路径遍历)
    - 创建上传目录
    - 写入文件到磁盘
    - 生成公开 URL (/uploads/{uuid}.jpg)
    - 推断媒体类型 (从扩展名)
    - 返回 Either<MediaError, UploadedMedia>
    ↓
UploadMediaUseCase
    - 传递 Repository 返回的结果
    - 返回 Either<MediaError, UploadedMedia>
    ↓
MediaRoutes.kt
    - 映射错误到 HTTP 状态码
    - 序列化 UploadedMedia 为 JSON
    - 返回 201 Created + {url, type}
    ↓
Client Response (201 Created)
{
    "url": "/uploads/abc123-def456.jpg",
    "type": "IMAGE"
}
```

---

## 配置管理

### application.yaml

```yaml
media:
  uploadDir: "uploads"                                    # 上传目录
  maxFileSize: 10485760                                  # 10MB in bytes
  allowedTypes: "image/jpeg,image/png,image/webp,video/mp4"
  enableDatabase: false                                  # 保留未来扩展
```

**特点**：
- 环境分离：开发/测试/生产可使用不同配置
- 运行时读取：无需重新编译
- 安全敏感值可使用环境变量覆盖

---

## 未来扩展点

### 1. 数据库元数据记录

为媒体记录数据库元数据，支持：
- 追踪谁上传了什么
- 统计存储空间占用
- 实现删除权限检查

```kotlin
// 新建 MediaMetadataTable
data class MediaMetadata(
    val id: String,
    val mediaUrl: String,
    val fileSize: Long,
    val contentType: String,
    val uploadedBy: UserId,
    val uploadedAt: Long
)

// DatabaseMediaStorageRepository 包装 FileSystemMediaStorageRepository
class DatabaseMediaStorageRepository(
    private val fileSystemRepository: FileSystemMediaStorageRepository,
    private val metadataRepository: MediaMetadataRepository
) : MediaStorageRepository {
    // 在上传时同时记录元数据
}
```

### 2. S3 存储实现

```kotlin
class S3MediaStorageRepository(
    private val s3Client: S3Client,
    private val bucketName: String
) : MediaStorageRepository {
    override suspend fun upload(file: ByteArray, fileName: String): Either<MediaError, UploadedMedia> {
        // S3 上传逻辑
        // 生成 CDN URL 或预签名 URL
    }
}

// DI 中根据配置切换
single<MediaStorageRepository> {
    val config = get<MediaConfig>()
    if (config.storageType == "s3") {
        S3MediaStorageRepository(s3Client, bucketName)
    } else {
        FileSystemMediaStorageRepository(config.uploadDir)
    }
}
```

### 3. OSS 存储实现

类似 S3，实现阿里云 OSS 适配器。

### 4. CDN 集成

为上传的 URL 添加 CDN 前缀：
```kotlin
val publicUrl = when {
    isDevelopment -> "/uploads/$fileName"
    isProduction -> "https://cdn.example.com/uploads/$fileName"
}
```

### 5. 多文件批量上传

当前实现是单文件上传（处理第一个文件后立即返回）。

未来升级到多文件时，改为：
```kotlin
// 收集所有文件结果，最后统一响应
val uploadedFiles = mutableListOf<UploadedMediaDto>()
val errors = mutableListOf<ErrorInfo>()

while (part != null) {
    if (part is PartData.FileItem) {
        // 处理文件，添加到 uploadedFiles 或 errors
        // 继续循环到所有 part 都处理完
    }
    part.dispose()
    part = multipart.readPart()
}

// 最后统一返回
call.respond(HttpStatusCode.Created, MultiFileUploadResponse(uploadedFiles, errors))
```

**为什么现在是单文件**：
- 简化逻辑，避免 HTTP 协议的多次响应问题
- 单文件模式更稳定、更易于测试和调试
- 客户端可以多次请求来完成批量上传

### 6. 异步处理和 SSE 推送

```kotlin
// 支持大文件上传进度推送
// 媒体处理完成后通过 SSE 通知客户端
```

---

## 关键设计原则

### ✅ DO
- 使用 Inline value class 确保类型安全 (`MediaId`)
- 使用 Value Objects 在构造时验证 (`MediaUrl`, `MediaConfig`)
- 错误作为值返回 (`Either<MediaError, T>`)
- Repository 接口在 Domain 层，实现在 Infrastructure 层
- UseCase 编排业务规则，不包含基础设施细节
- 文件名验证防止路径遍历攻击
- 配置从 YAML 读取，不硬编码
- **单个请求返回一个响应**（处理一个文件后立即返回）
- **正确推进循环变量**（避免无限循环或重复处理）

### ❌ DON'T
- Domain 层导入 Ktor/Exposed 框架代码
- 在 Route Handler 中编写业务逻辑
- 使用 String 表示 ID (用 MediaId 代替)
- 抛异常处理预期的业务错误 (用 Either 代替)
- 硬编码上传目录、限制大小等配置
- 信任客户端提供的原始文件名（始终使用安全的文件名生成逻辑）
- 过度设计 (YAGNI - 等第二个实现出现再抽象)

---

## 文件清单

### 新增文件

```
src/main/kotlin/
├── domain/
│   ├── failure/
│   │   └── MediaErrors.kt              # MediaError 密封接口定义
│   ├── model/
│   │   └── Media.kt                    # MediaId, MediaConfig, UploadedMedia
│   ├── repository/
│   │   └── MediaStorageRepository.kt   # 存储操作接口（Port）
│   └── usecase/
│       └── UploadMediaUseCase.kt       # 媒体上传业务逻辑
├── data/
│   └── repository/
│       └── FileSystemMediaStorageRepository.kt  # 文件系统存储实现
├── features/media/
│   ├── MediaMappers.kt                 # 请求/响应/错误转换
│   ├── MediaSchema.kt                  # DTO 定义
│   └── MediaRoutes.kt                  # HTTP 路由（重构）
├── core/di/
│   └── MediaModule.kt                  # Koin DI 配置
└── resources/
    └── application.yaml                # 媒体配置参数
```

---

## 下一步实现清单

- [x] Domain layer: MediaId, MediaConfig, UploadedMedia models
- [x] Domain layer: MediaError 错误类型
- [x] Domain layer: MediaStorageRepository interface
- [x] UseCase layer: UploadMediaUseCase
- [x] Data layer: FileSystemMediaStorageRepository
- [x] Feature layer: MediaMappers
- [x] Feature layer: MediaRoutes (重构)
- [x] DI: MediaModule 注册
- [x] Config: application.yaml 媒体配置
- [ ] Metadata layer: MediaMetadataTable（可选）
- [ ] Data layer: DatabaseMediaStorageRepository（可选）
- [ ] Data layer: S3MediaStorageRepository（可选）
- [ ] Data layer: OSSMediaStorageRepository（可选）
- [ ] Tests: UploadMediaUseCaseTest
- [ ] Tests: FileSystemMediaStorageRepositoryTest
- [ ] Tests: MediaRoutesTest

---

**设计完成！** 🎉

整个媒体功能已实现完整的六边形架构，支持从文件系统轻松迁移到 S3/OSS，同时保持业务逻辑独立于具体存储实现。
