# API 服务文档

## 功能文档

### Auth 功能（认证与会话）

- **[Auth Token 系统设计文档](./auth-refresh-token-system.md)** - JWT + Refresh Token + Token Family 的现有设计
- **[Auth 根因级重构修复方案](./auth-root-cause-refactor-plan.md)** - 从源头修复并发与鉴权语义问题的重构方案

### Post 功能（推文/评论系统）

- **[Post 功能设计文档](./post-feature-design.md)** - 架构设计、数据模型、Repository 接口、UseCase、错误处理策略
- **[Post 功能实现总结](./post-feature-implementation.md)** - Infrastructure 层、Transport 层、DI 配置、API 测试示例、已知限制和未来优化

### Block 功能（拉黑与删除 Post）

- **[Block 功能设计文档](./block-feature-design.md)** - 拉黑模型、数据库设计、UseCase 编排、内容过滤策略、跨功能影响分析
- **[Block & 删除 Post 客户端接入指南](./block-client-integration-guide.md)** - 拉黑/取消拉黑/删除 Post API、错误码、客户端代码示例、测试清单

### Media 功能（媒体上传系统）

- **[Media 功能设计文档](./media-feature-design.md)** - 六边形架构、Domain Models、Repository 接口、UseCase 设计、错误处理、配置管理
- **[Media 功能实现总结](./media-feature-implementation.md)** - Domain 层、UseCase 层、Data 层、Feature 层、DI 配置、API 使用示例、未来扩展

### 基础设施

- **[日志配置文档](./LOGGING.md)** - 日志系统配置、级别设置、输出格式
- **[日志快速参考](./LOG_QUICK_REF.md)** - 日志查找命令、常见问题排查

---

## 架构概览

整个项目遵循 **Hexagonal Architecture (六边形架构)** 和 **Domain-Driven Design (DDD)** 原则：

```
Domain Layer
    ├─ Models (Post, User, Follow, Block, Message, etc.)
    ├─ Errors (PostError, UserError, MessageError, etc.)
    ├─ Repositories (PostRepository, UserRepository, MessageRepository)
    └─ UseCases (CreatePostUseCase, BlockUserUseCase, SendMessageUseCase, etc.)
            ↓
Infrastructure Layer
    ├─ Database (ExposedPostRepository, ExposedUserRepository)
    └─ Storage (FileSystemMediaStorageRepository)
            ↓
Feature/Transport Layer
    ├─ Routes (PostRoutes, MediaRoutes)
    ├─ Schemas (DTOs)
    └─ Mappers (Domain ↔ HTTP conversions)
```

---

## 快速启动

### 前置条件

- Kotlin 1.9+
- Java 17+
- PostgreSQL 13+ (数据库)

### 构建和运行

```bash
# 构建项目
./gradlew build

# 运行应用
./gradlew run

# 或使用 Java 直接运行
java -jar build/libs/app-all.jar
```

### 默认配置

- **服务器**: http://localhost:8080
- **数据库**: PostgreSQL (localhost:5432)
- **上传目录**: ./uploads

---

## 主要 API 端点

### 认证

- `POST /v1/auth/register` - 用户注册
- `POST /v1/auth/login` - 用户登录

### Post（推文）

- `POST /v1/posts` - 创建 Post（需认证）
- `DELETE /v1/posts/{postId}` - 删除 Post（需认证，仅作者）
- `GET /v1/posts/timeline` - 获取时间线
- `GET /v1/posts/{postId}` - 获取 Post 详情
- `GET /v1/posts/{postId}/replies` - 获取回复列表
- `GET /v1/posts/users/{userId}` - 获取用户的 Posts

### 用户（User）

- `GET /v1/users/{userId}` - 获取用户资料
- `GET /v1/users/username/{username}` - 通过 username 获取用户资料
- `PATCH /v1/users/me` - 更新当前用户资料（需认证）
- `POST /v1/users/{userId}/follow` - 关注用户（需认证）
- `DELETE /v1/users/{userId}/follow` - 取消关注（需认证）
- `POST /v1/users/{userId}/block` - 拉黑用户（需认证）
- `DELETE /v1/users/{userId}/block` - 取消拉黑（需认证）
- `GET /v1/users/{userId}/following` - 获取关注列表
- `GET /v1/users/{userId}/followers` - 获取粉丝列表
- `GET /v1/users/{userId}/posts` - 获取用户的 Posts
- `GET /v1/users/{userId}/replies` - 获取用户的回复
- `GET /v1/users/{userId}/likes` - 获取用户的点赞

### Media（媒体）

- `POST /v1/media/upload` - 上传媒体文件（需认证）
- `GET /uploads/{fileName}` - 访问已上传的文件

---

## 设计原则

### ✅ 推荐做法

- 使用 Inline Value Class 确保类型安全（PostId, MediaId）
- Value Objects 在构造时验证（PostContent, MediaUrl）
- 错误作为值返回（Either<Error, T>）
- Repository 接口在 Domain 层，实现在 Infrastructure 层
- UseCase 编排业务规则，不包含基础设施细节
- 配置从 YAML 读取，不硬编码

### ❌ 避免做法

- Domain 层导入框架代码（Ktor, Exposed, Spring 等）
- 在 Route Handler 中编写业务逻辑
- 使用基本类型表示 ID（用 PostId 代替 String）
- 抛异常处理预期的业务错误（用 Either 代替）
- 硬编码配置参数
- 过度工程化（YAGNI 原则）

---

## 文件结构

```
src/main/kotlin/
├── domain/                      # 业务规则层（无框架依赖）
│   ├── failure/                 # 错误定义
│   ├── model/                   # 领域模型
│   ├── repository/              # Repository 接口（Port）
│   ├── service/                 # 领域服务
│   └── usecase/                 # 业务用例
├── data/                        # 数据和基础设施层
│   ├── db/                      # 数据库配置和映射
│   └── repository/              # Repository 实现（Adapter）
├── features/                    # 功能模块（HTTP 适配）
│   ├── auth/
│   ├── post/
│   ├── user/
│   ├── messaging/
│   ├── search/
│   ├── notification/
│   └── media/
├── core/                        # 核心功能
│   ├── di/                      # 依赖注入配置
│   └── security/                # 安全和认证
└── plugins/                     # Ktor 插件配置
```

---

## 开发流程

### 添加新功能

1. **设计 Domain 层**
   - 定义 Models 和 Value Objects
   - 定义 Errors（sealed interface）
   - 定义 Repository 接口（Port）

2. **实现 UseCase 层**
   - 编排业务规则
   - 调用 Repository
   - 返回 Either<Error, Result>

3. **实现 Infrastructure 层**
   - 实现 Repository 接口（Adapter）
   - 处理数据库操作
   - 错误转换

4. **实现 Feature 层**
   - 定义 DTOs（Schema）
   - 创建 Mappers
   - 定义 Routes

5. **配置 DI**
   - 创建 Module
   - 注册 beans
   - 读取配置

6. **更新文档**
   - 设计文档
   - 实现总结
   - API 示例

---

## 测试

### 单元测试

```bash
./gradlew test
```

### 集成测试

```bash
./gradlew integrationTest
```

### 运行特定测试

```bash
./gradlew test --tests PostUseCaseTest
```

---

## 常见问题

### Q: 如何修改上传文件大小限制？

A: 编辑 `application.yaml` 中的 `media.maxFileSize` 参数（单位字节）

### Q: 如何切换到 S3 存储？

A: 实现 `S3MediaStorageRepository` 类，在 `MediaModule.kt` 中根据配置切换

### Q: Domain 层可以依赖 Arrow 库吗？

A: 可以。Arrow 的函数式编程工具（Either, Option 等）是编程模式，不属于基础设施框架

### Q: 如何处理大文件上传？

A: 实现分块上传，使用 Flow 支持流式处理

---

## 性能优化建议

1. **数据库索引**：为 authorId, parentId, createdAt 添加索引
2. **缓存**：Redis 缓存热门 Post
3. **分页优化**：使用游标分页替代 offset/limit
4. **CDN**：将上传的文件通过 CDN 分发
5. **消息队列**：异步处理媒体处理任务

---

## 部署

### Docker 部署

```dockerfile
FROM openjdk:17-slim
COPY build/libs/app-all.jar app.jar
CMD ["java", "-jar", "app.jar"]
```

### 环境变量

```bash
DATABASE_URL=jdbc:postgresql://host:5432/db
DATABASE_USER=user
DATABASE_PASSWORD=password
MEDIA_UPLOAD_DIR=/data/uploads
JWT_SECRET=your-secret-key
```

---

## 贡献指南

- 遵循现有的分层架构
- 新代码必须通过单元测试
- 提交时更新相关文档
- 遵循 Commit Message 规范

---

**最后更新**: 2026-02-13
