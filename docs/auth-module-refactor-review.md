# 用户模块（注册/登录）重构评审文档

更新时间：2026-02-08  
范围：`/v1/auth/register`、`/v1/auth/login` 及其相关 Domain/Application/Infrastructure/Transport 代码  
约束：保持简单实用，避免过度工程化；`application.yaml` 明文配置当前允许

---

## 1. 结论摘要

当前用户模块可以运行，但距离“专业架构级”还有明显差距，核心问题不是功能缺失，而是边界和约束不稳定。  
优先级最高的改造点有 3 个：

1. 输入边界不完整（`email` 规范化、`displayName` 无领域约束），会把用户输入错误延迟到数据库层。
2. 路由层异常处理重复且过宽（`catch Exception` 后再抛），造成重复日志与噪音。
3. 基础设施层唯一键错误映射过于粗糙（按 `sqlState` 大类），后续扩展唯一约束时会误判业务错误。

只做这 3 项，就能显著提升稳定性和可维护性，且改动成本可控。

---

## 2. 发现清单（按严重级别）

### 2.1 High：输入边界缺失，业务规则未前置

证据：
- `src/main/kotlin/domain/model/User.kt:14` 仅做邮箱正则校验，没有 `trim/lowercase` 规范化。
- `src/main/kotlin/domain/usecase/RegisterUseCase.kt:46` 直接使用 `displayName` 原始字符串构造用户。
- `src/main/kotlin/data/db/schema/UsersTable.kt:17` 数据库 `display_name` 长度上限 64，但 Domain 未约束。

风险：
- `" A@B.com "`、`"a@b.com"` 行为不一致，可能出现逻辑重复账号。
- 超长 `displayName` 进入数据库层才失败，最终表现为 500，而不是可预期 400。

建议（务实版）：
- 在 Domain 增加 `DisplayName` Value Object（`trim` + 长度校验）。
- `Email` 构造时做标准化（`trim` + `lowercase(Locale.ROOT)`）后再校验。
- 新增 `AuthError.InvalidDisplayName`，在 Transport 映射为 400。

---

### 2.2 High：AuthRoutes 异常处理重复，日志有双重记录风险

证据：
- `src/main/kotlin/features/auth/AuthRoutes.kt:29`、`src/main/kotlin/features/auth/AuthRoutes.kt:88` 包裹 `try/catch (Exception)`。
- `src/main/kotlin/features/auth/AuthRoutes.kt:77`、`src/main/kotlin/features/auth/AuthRoutes.kt:136` 捕获后再次抛出。
- `src/main/kotlin/plugins/StatusPages.kt:41` 已有全局 `Throwable` 处理。

风险：
- 同一异常会在 Route 和 StatusPages 都记录，增加排障噪音。
- Route 里的通用异常日志与全局异常处理职责重叠，边界不清晰。

建议（务实版）：
- 移除 Route 的通用 `try/catch`，交给 `StatusPages` 统一处理。
- Route 仅处理业务 `Either` 左值（`AuthError`）。
- 若保留本地捕获，只捕获明确可恢复异常，不捕获 `Exception`。

---

### 2.3 High：唯一约束错误映射过于粗粒度

证据：
- `src/main/kotlin/data/repository/ExposedUserRepository.kt:43` 通过 `sqlState` 直接映射 `UserAlreadyExists`。

风险：
- 将来 users 表新增其他唯一约束（例如昵称唯一）后，仍会被误判成“邮箱已存在”。
- 业务错误语义与数据库细节耦合，未来扩展时隐患大。

建议（务实版）：
- 按约束名判断（如 PostgreSQL `users_email_key`）再映射 `UserAlreadyExists`。
- 非邮箱唯一冲突走“未知数据库异常”路径（保持 500），避免错误语义污染。

---

### 2.4 Medium：全局错误响应类型放在 auth feature，模块耦合方向反了

证据：
- `src/main/kotlin/plugins/StatusPages.kt:3` 依赖 `com.connor.features.auth.ErrorResponse`。
- `src/main/kotlin/features/auth/AuthSchema.kt:28` 定义了 `ErrorResponse`（其实是全局通用概念）。

风险：
- `plugins`（全局层）反向依赖 `feature`（业务模块），不利于模块边界清晰。

建议（务实版）：
- 把 `ErrorResponse` 提升到 `core/http`（如 `ApiErrorResponse`）。
- `StatusPages`、`auth/post/media` 统一复用该类型。

---

### 2.5 Medium：UseCase 放在 domain 包且直接依赖 SLF4J

证据：
- `src/main/kotlin/domain/usecase/RegisterUseCase.kt:11`
- `src/main/kotlin/domain/usecase/LoginUseCase.kt:10`

风险：
- 若严格执行“Domain 纯 Kotlin”，当前包层级语义会混淆（Domain 与 Application 服务界限不清）。

建议（务实版）：
- 最小改法：保留代码逻辑，先迁移包到 `application/usecase`。
- 日志可先保留，再逐步收敛为“入口层 + 基础设施层”记录，避免一次性大改。

---

### 2.6 Medium：注册/登录 Route 重复代码较多

证据：
- `src/main/kotlin/features/auth/AuthRoutes.kt:22-79` 与 `src/main/kotlin/features/auth/AuthRoutes.kt:81-138` 高度重复（计时、日志、fold、响应）。

风险：
- 修改一个流程容易漏另一个流程；行为漂移概率上升。

建议（务实版）：
- 提取一个小型私有 helper 处理“调用 use case + fold + 统一日志”。
- 不引入额外抽象层（例如复杂模板方法/基类），仅函数级复用。

---

### 2.7 Low：密码校验吞异常过宽

证据：
- `src/main/kotlin/infrastructure/service/BCryptPasswordHasher.kt:33` 捕获所有异常后返回 `false`。

风险：
- 错误 hash 格式、库异常与密码错误都被同化，定位困难。

建议（务实版）：
- 只捕获已知异常类型并记录 `debug/warn`。
- 外部响应仍然返回 `InvalidCredentials`，避免信息泄露。

---

## 3. 目标架构（简单且可落地）

保持当前分层思路，不做“重写式重构”，只做边界收敛：

1. Transport 层：只做 DTO 反序列化、调用 use case、映射 HTTP。
2. Application 层：编排注册/登录流程，不感知 Ktor/Exposed。
3. Domain 层：类型与规则（Email、DisplayName、AuthError）。
4. Infrastructure 层：数据库和加密实现，负责技术细节到领域错误的转换。
5. Core 层：全局通用协议模型（如统一 ErrorResponse）。

---

## 4. 分模块重构方案（执行清单）

### 4.1 Domain 模块（优先做）

目标：把输入规则前置到领域，避免数据库兜底。

改造项：
1. 新增 `DisplayName` Value Object。  
2. `Email` 增加规范化逻辑。  
3. `AuthError` 增加 `InvalidDisplayName`。  
4. `User` 改为持有 `displayName: DisplayName`（或折中：先在 `RegisterUseCase` 内验证，暂不改 User 字段类型）。

建议折中（更快落地）：
- 第一阶段先不改 `User` 字段类型，先在 `RegisterUseCase` 验证 `displayName` 并返回 `AuthError.InvalidDisplayName`。  
- 第二阶段再把 `String` 升级为 Value Object，避免一次性连锁改动过大。

---

### 4.2 Application/UseCase 模块（优先做）

目标：流程编排稳定、错误路径明确。

改造项：
1. 将 `domain/usecase` 迁移到 `application/usecase`（仅包语义和导包调整，不改业务逻辑）。  
2. `RegisterUseCase` 增加 displayName 校验。  
3. 保持 `Either<AuthError, User>`，不引入额外泛型层。  
4. 先保留日志，再逐步收敛日志位置（避免大规模行为变化）。

说明：
- 不建议此阶段引入 `AuthService`、`Facade`、`CommandBus` 等抽象，收益低于复杂度成本。

---

### 4.3 Infrastructure（Repository + Password）模块（优先做）

目标：错误映射准确，避免误判。

改造项：
1. `ExposedUserRepository.save`：按“邮箱唯一约束名”映射 `UserAlreadyExists`。  
2. 非目标约束冲突不做业务化映射，交给全局异常处理（500）。  
3. `BCryptPasswordHasher.verify` 收敛捕获范围，记录可观测日志。

---

### 4.4 Transport（AuthRoutes/AuthMappers）模块（优先做）

目标：路由薄化、行为统一、减少重复。

改造项：
1. 去掉 `AuthRoutes` 中通用 `try/catch(Exception)`。  
2. 提取通用响应 helper，消除 register/login 重复流程。  
3. `AuthError.toHttpError()` 新增 `InvalidDisplayName -> 400`。  
4. 统一日志字段，邮箱做脱敏（例如 `a***@xx.com`）。

---

### 4.5 Core/Plugins（第二优先级）

目标：去除全局层到 feature 层的反向依赖。

改造项：
1. 把 `ErrorResponse` 从 `features/auth/AuthSchema.kt` 提升到 `core/http`。  
2. `StatusPages` 改依赖 `core/http` 类型。  
3. Auth/Post/Media 都复用同一错误响应结构。

---

## 5. 推荐实施顺序（2 个迭代）

### 迭代 A（必须做）

1. Domain：`Email` 规范化 + `displayName` 校验 + `AuthError.InvalidDisplayName`。  
2. AuthRoutes：移除通用 `try/catch`，统一 fold + 响应。  
3. ExposedUserRepository：唯一约束映射改为“按约束名判断”。  
4. AuthMappers：新增新错误映射，保持响应契约不破坏。

收益：
- 立即减少 500 与重复日志。
- 让注册/登录失败路径稳定可预期（400/401/409）。

---

### 迭代 B（建议做）

1. `ErrorResponse` 提升到 `core/http`。  
2. 包语义优化：`domain/usecase` -> `application/usecase`。  
3. 日志脱敏规则统一（email/ip/userAgent 截断策略统一）。

收益：
- 边界更清晰，后续加 refresh token/验证码时不容易散架。

---

## 6. 验收标准（交付给开发可直接打勾）

1. `displayName` 非法输入返回 400，错误码稳定。  
2. 邮箱大小写和首尾空格被规范化，不出现逻辑重复账号。  
3. AuthRoutes 中不再出现通用 `catch (Exception)`。  
4. 唯一约束冲突只在“邮箱唯一冲突”时映射为 `USER_EXISTS`。  
5. 全局错误响应类型不再定义在 `features/auth`。  
6. register/login 的公共流程代码有函数级复用，重复显著减少。  
7. API 契约保持兼容（`/v1/auth/register`、`/v1/auth/login` 路径与主字段不变）。

---

## 7. 最小测试清单（避免过度测试）

建议新增最小必需测试，不追求大而全：

1. `RegisterUseCase`：非法邮箱、弱密码、非法 displayName、重复邮箱。  
2. `LoginUseCase`：用户不存在、密码错误、成功登录。  
3. `AuthRoutes` 集成测试：400/401/409/201/200 映射。  
4. `ExposedUserRepository` 集成测试：邮箱唯一约束映射。

说明：
- 当前项目无 `src/test`，可先补最关键的 use case 与 route 测试。

---

## 8. 明确不做（防止过度工程化）

1. 不引入新框架（如 Spring Security、复杂认证中间件）。  
2. 不拆分微服务。  
3. 不在本轮引入 refresh token、黑名单、设备管理。  
4. 不做“为未来可能性”而引入多实现抽象（YAGNI）。

---

## 9. 备注

本评审基于当前代码快照，重点是“架构边界清晰 + 行为可预测 + 简单可落地”。  
如果你希望，我可以下一步直接给出“迭代 A”的逐文件改造任务单（按文件和提交顺序拆到开发可直接执行）。
