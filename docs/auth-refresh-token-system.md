# Auth Token 系统设计文档

**更新时间**: 2026-02-11
**状态**: 已实现
**范围**: JWT 短期令牌 + Refresh Token 轮换 + 敏感路由 + WebSocket 强制下线

---

## 1. 架构概览

### 1.1 核心设计原则

- **JWT 无状态快速验证**：3分钟有效期 + 15秒服务端 leeway，普通接口不查库
- **Refresh Token 长期登录**：14天有效期，Token Rotation 确保安全
- **双重保险机制**：WebSocket 主动推送 + 敏感路由数据库校验，无需 Redis
- **Token Family 防重放**：同一登录会话的 token 共享 familyId，检测到旧 token 被重用时撤销整个 family

### 1.2 安全层级

```
┌──────────────────────────────────────────────────────┐
│ 第1层: JWT 签名验证（每个请求，无状态，0ms 开销）      │
│   → 验证签名、过期时间、audience、issuer              │
│   → 适用于: GET /timeline, POST /like 等普通接口      │
├──────────────────────────────────────────────────────┤
│ 第2层: 敏感路由 DB 校验（按需查库，~5ms 开销）         │
│   → 查库确认用户存在 + passwordChangedAt < issuedAt   │
│   → 适用于: POST /change-password, DELETE /account   │
├──────────────────────────────────────────────────────┤
│ 第3层: WebSocket 主动推送（实时，服务端发起）           │
│   → 密码修改/异常登录时主动通知所有客户端下线           │
│   → 覆盖场景: 用户改密码后旧设备立即感知               │
└──────────────────────────────────────────────────────┘
```

---

## 2. Token 生命周期

### 2.1 Token 参数

| 参数 | 值 | 说明 |
|------|-----|------|
| JWT 有效期 | 3 分钟 | 短期无状态令牌（配合 15 秒服务端 leeway，实际窗口 ~3:15） |
| Refresh Token 有效期 | 14 天 | 长期登录凭证 |
| JWT 服务端 Leeway | 15 秒 | 处理慢网络和时钟漂移，对 exp/iat/nbf 统一生效 |
| 并发刷新宽限期 | 10 秒 | 多设备/多 tab 同时刷新时的容错窗口 |
| JWT 算法 | HMAC256 | 对称签名 |
| Refresh Token 格式 | 48字节随机 hex | 96字符，SecureRandom 生成 |
| Refresh Token 存储 | SHA-256 哈希 | 数据库只存哈希值，泄露无法还原 |

### 2.2 JWT Claims

```json
{
  "aud": "jwt-audience",
  "iss": "http://localhost/",
  "id": "用户ID",
  "displayName": "显示名称",
  "username": "用户名",
  "iat": 1707600000,
  "exp": 1707601200
}
```

- `iat` (issuedAt): 签发时间戳（毫秒），敏感路由用于对比 `passwordChangedAt`
- `exp`: 过期时间 = `iat` + 3分钟

---

## 3. 数据库设计

### 3.1 refresh_tokens 表

```sql
CREATE TABLE refresh_tokens (
    id              VARCHAR(36) PRIMARY KEY,
    token_hash      VARCHAR(128) UNIQUE NOT NULL,  -- SHA-256 哈希，不存明文
    user_id         VARCHAR(36) NOT NULL,           -- 关联用户
    family_id       VARCHAR(36) NOT NULL,           -- Token Family（同一登录会话）
    expires_at      BIGINT NOT NULL,                -- 过期时间戳
    is_revoked      BOOLEAN DEFAULT FALSE,          -- 是否已撤销
    created_at      BIGINT NOT NULL                 -- 创建时间戳
);

CREATE UNIQUE INDEX ON refresh_tokens (token_hash);
CREATE INDEX ON refresh_tokens (user_id);
CREATE INDEX ON refresh_tokens (family_id);
```

### 3.2 users 表新增字段

```sql
ALTER TABLE users ADD COLUMN password_changed_at BIGINT DEFAULT 0;
```

用途：敏感路由校验 `passwordChangedAt > tokenIssuedAt` 时拒绝旧 token。

### 3.3 Token Family 说明

```
用户登录 → 生成 familyId = UUID
  ├── RefreshToken_1 (familyId=abc, active)
  │     ↓ 刷新
  ├── RefreshToken_1 (familyId=abc, revoked)
  ├── RefreshToken_2 (familyId=abc, active)    ← 新 token
  │     ↓ 刷新
  ├── RefreshToken_2 (familyId=abc, revoked)
  └── RefreshToken_3 (familyId=abc, active)    ← 最新 token

如果此时有人重用 RefreshToken_1：
  → 超出 10s 宽限期 → 整个 family 全部撤销
  → WebSocket 推送 auth_revoked 给该用户所有设备
```

---

## 4. API 接口

### 4.1 POST /v1/auth/register

注册并自动登录，返回 JWT + Refresh Token。

**请求**:
```json
{
  "email": "user@example.com",
  "password": "securePass123",
  "displayName": "张三"
}
```

**成功响应** (201 Created):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "username": "user_550e8400",
  "displayName": "张三",
  "bio": "",
  "avatarUrl": null,
  "createdAt": 1707600000000,
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "a1b2c3d4e5f6...（96字符hex）",
  "expiresIn": 180000
}
```

**字段说明**:
- `token`: JWT，3分钟有效，放入 `Authorization: Bearer <token>` 请求头
- `refreshToken`: 刷新令牌，14天有效，客户端安全存储（如 EncryptedSharedPreferences）
- `expiresIn`: JWT 有效期毫秒数（180000 = 3分钟），客户端可用于提前刷新（建议在 80% 生命周期时刷新）

**错误响应**:

| 状态码 | code | 场景 |
|--------|------|------|
| 400 | INVALID_EMAIL | 邮箱格式错误 |
| 400 | WEAK_PASSWORD | 密码强度不足 |
| 400 | INVALID_DISPLAY_NAME | 昵称格式错误 |
| 409 | USER_EXISTS | 邮箱已注册 |

---

### 4.2 POST /v1/auth/login

登录，返回 JWT + Refresh Token。

**请求**:
```json
{
  "email": "user@example.com",
  "password": "securePass123"
}
```

**成功响应** (200 OK):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "username": "user_550e8400",
  "displayName": "张三",
  "bio": "",
  "avatarUrl": null,
  "createdAt": 1707600000000,
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "a1b2c3d4e5f6...",
  "expiresIn": 180000
}
```

**错误响应**:

| 状态码 | code | 场景 |
|--------|------|------|
| 400 | INVALID_EMAIL | 邮箱格式错误 |
| 401 | AUTH_FAILED | 邮箱或密码错误（模糊报错，防枚举） |

---

### 4.3 POST /v1/auth/refresh

Token 刷新端点。**不需要 JWT 认证**（因为 JWT 已过期才会调用此接口）。

**请求**:
```json
{
  "refreshToken": "a1b2c3d4e5f6...（之前获得的 refresh token）"
}
```

**成功响应** (200 OK):
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...（新 JWT）",
  "refreshToken": "x9y8z7w6v5u4...（新 refresh token）",
  "expiresIn": 180000
}
```

**重要**: 每次刷新后旧的 refresh token 立即失效，必须使用响应中的新 refresh token。这就是 Token Rotation。

**错误响应**:

| 状态码 | code | 场景 | 客户端处理 |
|--------|------|------|-----------|
| 401 | REFRESH_TOKEN_INVALID | token 不存在或格式错误 | 跳转登录页 |
| 401 | REFRESH_TOKEN_EXPIRED | token 已过期（>14天） | 跳转登录页 |
| 401 | TOKEN_REUSE_DETECTED | 检测到旧 token 被重用（疑似被盗） | 跳转登录页 + 提示安全风险 |
| 409 | STALE_REFRESH_TOKEN | token 已被并发请求轮换（宽限期内） | 重读本地最新 token + 重试原请求 |

---

### 4.4 敏感路由 (Sensitive Routes)

敏感路由在 JWT 验证之上增加数据库校验。如果用户密码在 JWT 签发后被修改，或用户已被删除/禁用，返回 401。

**额外错误响应**（仅敏感路由可能返回）:

| 状态码 | code | 场景 |
|--------|------|------|
| 401 | SESSION_REVOKED | 密码已修改 / 用户不存在 / 账号已禁用 |

客户端收到 `SESSION_REVOKED` 时应清除所有 token 并跳转登录页。

---

### 4.5 WebSocket 认证事件

WebSocket 端点 `/v1/notifications/ws` 会收到以下认证相关推送消息：

**auth_revoked** — 强制下线通知:
```json
{
  "type": "auth_revoked",
  "message": "会话已失效，请重新登录"
}
```

触发场景：
- 用户在其他设备修改了密码
- 检测到 Token Reuse Attack（旧 refresh token 被重放）
- 管理员手动撤销用户会话

客户端收到此消息后应立即：
1. 清除本地存储的 JWT 和 Refresh Token
2. 关闭 WebSocket 连接
3. 跳转到登录页
4. 显示提示信息（使用 `message` 字段）

---

## 5. 路由安全分级

### 5.1 服务端路由配置

```kotlin
routing {
    // ========== 公开路由（无需认证）==========
    route("/v1/auth") {
        post("/register") { ... }
        post("/login") { ... }
        post("/refresh") { ... }   // ← 不需要JWT，靠refresh token自身验证
    }

    // ========== 普通路由（快，无状态，不查库）==========
    authenticate("auth-jwt") {
        get("/v1/timeline") { ... }
        post("/v1/posts") { ... }
        post("/v1/posts/{id}/like") { ... }
        get("/v1/users/{username}") { ... }
        // ... 其他普通CRUD接口
    }

    // ========== 敏感路由（慢，有状态，强制查库）==========
    authenticate("auth-jwt") {
        sensitive(userRepository) {
            post("/v1/auth/change-password") { ... }
            delete("/v1/account") { ... }
            post("/v1/payment/confirm") { ... }
        }
    }
}
```

### 5.2 分级判断标准

| 级别 | 条件 | 示例接口 |
|------|------|---------|
| 公开 | 不需要知道用户身份 | register, login, refresh |
| 普通认证 | 只需确认"是合法用户" | timeline, like, follow |
| 敏感认证 | 需要确认"用户当前状态安全" | 改密码, 删账号, 支付 |

---

## 6. 完整流程图

### 6.1 正常使用流程

```
客户端                              服务端
  │                                   │
  │── POST /v1/auth/login ──────────→│
  │                                   │── 验证邮箱密码
  │                                   │── 生成 JWT(3min) + RefreshToken(14天)
  │                                   │── 存储 RefreshToken 哈希到数据库
  │←── { token, refreshToken } ──────│
  │                                   │
  │  （正常使用 JWT 访问接口...）        │
  │── GET /v1/timeline ──────────────→│
  │   Authorization: Bearer <jwt>     │── JWT 签名验证（不查库）
  │←── { posts: [...] } ─────────────│
  │                                   │
  │  （3分钟后 JWT 过期...）           │
  │── GET /v1/timeline ──────────────→│
  │   Authorization: Bearer <jwt>     │── JWT 过期
  │←── 401 INVALID_TOKEN ────────────│
  │                                   │
  │── POST /v1/auth/refresh ─────────→│
  │   { refreshToken: "old_token" }   │── 验证 refresh token
  │                                   │── Token Rotation: 旧token撤销
  │                                   │── 生成新 JWT + 新 RefreshToken
  │←── { token, refreshToken } ──────│
  │                                   │
  │  （用新 JWT 重试原请求...）         │
  │── GET /v1/timeline ──────────────→│
  │   Authorization: Bearer <new_jwt> │── 验证通过
  │←── { posts: [...] } ─────────────│
```

### 6.2 密码修改后的强制下线流程

```
设备A（修改密码）                  服务端                    设备B（旧会话）
  │                                │                          │
  │── POST /change-password ──────→│                          │
  │   (sensitive 路由，查库通过)     │                          │
  │                                │── 更新 passwordChangedAt  │
  │                                │── 撤销该用户所有 RefreshToken
  │                                │── WebSocket 推送 ────────→│
  │                                │   { type: "auth_revoked" }│
  │←── 200 OK ────────────────────│                          │
  │                                │                          │── 收到推送
  │                                │                          │── 清除本地token
  │                                │                          │── 跳转登录页
```

### 6.3 Token Reuse 攻击检测流程

```
合法客户端                          服务端                    攻击者
  │                                  │                          │
  │  （正常刷新 token）                │                          │
  │── POST /refresh ────────────────→│                          │
  │   { refreshToken: "token_1" }    │── 撤销 token_1            │
  │                                  │── 生成 token_2            │
  │←── { token_2 } ─────────────────│                          │
  │                                  │                          │
  │                                  │          （攻击者重放 token_1）
  │                                  │←── POST /refresh ────────│
  │                                  │    { refreshToken: "token_1" }
  │                                  │                          │
  │                                  │── token_1 已撤销           │
  │                                  │── 检查: 超出10s宽限期？     │
  │                                  │── 是 → Reuse Attack!      │
  │                                  │── 撤销整个 family           │
  │                                  │── WebSocket推送 ──────────→│（如果在线）
  │   ← WebSocket推送 ──────────────│                          │
  │   { type: "auth_revoked",        │←── 401 TOKEN_REUSE ──────│
  │     message: "检测到异常登录..." } │                          │
  │                                  │                          │
  │── 收到推送，清除token，重新登录    │                          │
```

---

## 7. 错误码速查表

| HTTP 状态码 | code | 含义 | 客户端处理 |
|-------------|------|------|-----------|
| 400 | INVALID_EMAIL | 邮箱格式错误 | 提示用户修改 |
| 400 | WEAK_PASSWORD | 密码强度不足 | 提示密码规则 |
| 400 | INVALID_DISPLAY_NAME | 昵称格式错误 | 提示昵称规则 |
| 401 | INVALID_TOKEN | JWT 无效或过期 | 尝试 refresh，失败则登录 |
| 401 | AUTH_FAILED | 邮箱或密码错误 | 提示重新输入 |
| 401 | REFRESH_TOKEN_INVALID | Refresh token 无效 | 跳转登录页 |
| 401 | REFRESH_TOKEN_EXPIRED | Refresh token 过期 | 跳转登录页 |
| 401 | TOKEN_REUSE_DETECTED | 检测到 token 被盗用 | 跳转登录页 + 安全提示 |
| 401 | SESSION_REVOKED | 会话已失效（密码已改等） | 跳转登录页 |
| 409 | USER_EXISTS | 邮箱已注册 | 提示已注册 |
| 409 | STALE_REFRESH_TOKEN | token 已被并发请求轮换 | 重读本地最新 token + 重试 |

---

## 8. 与旧版对比

| 特性 | 旧版 | 新版 |
|------|------|------|
| JWT 有效期 | 14 天 | 3 分钟（+ 15秒 leeway） |
| 长期登录 | 依赖长期 JWT | Refresh Token (14天) + Token Rotation |
| Token 验证 | `GET /v1/auth/validate`（客户端轮询） | WebSocket 主动推送（服务端发起） |
| 敏感操作校验 | 无 | `sensitive {}` 路由插件，查库验证 |
| Token 被盗检测 | 无 | Token Family Reuse Detection |
| 强制下线 | 无 | WebSocket `auth_revoked` 推送 |
| 密码修改后 | 旧 JWT 仍有效 14 天 | 旧 JWT 最多 3 分钟失效 + 敏感路由立即拦截 |
| 依赖 | 无 | 无（不依赖 Redis） |

### 已移除的端点

- ~~`GET /v1/auth/validate`~~ — 不再需要。JWT 验证由中间件自动完成，强制下线通过 WebSocket 推送。

---

## 9. 文件清单

### 新增文件

| 文件路径 | 职责 |
|---------|------|
| `domain/model/RefreshToken.kt` | RefreshToken 领域实体（含 family 追踪） |
| `domain/repository/RefreshTokenRepository.kt` | Repository 接口（Domain Port） |
| `data/db/schema/RefreshTokensTable.kt` | 数据库表定义 |
| `data/repository/ExposedRefreshTokenRepository.kt` | Exposed 实现 |
| `domain/usecase/RefreshTokenUseCase.kt` | Token 轮换 + Reuse Detection 逻辑 |
| `core/security/SensitiveRoutePlugin.kt` | `sensitive {}` 路由 DSL |

### 修改文件

| 文件路径 | 改动 |
|---------|------|
| `domain/failure/AuthErrors.kt` | +5 个新错误类型 |
| `domain/model/User.kt` | +`passwordChangedAt` 字段 |
| `core/security/TokenService.kt` | JWT 20min, +`issuedAt`, +`RefreshTokenService`, +配置项 |
| `core/security/UserPrincipal.kt` | +`issuedAt` 字段 |
| `plugins/Security.kt` | 提取 `issuedAt` 从 JWT payload |
| `data/db/schema/UsersTable.kt` | +`passwordChangedAt` 列 |
| `data/db/mapping/UserMapping.kt` | 映射 `passwordChangedAt` |
| `data/db/DatabaseFactory.kt` | +`RefreshTokensTable` 自动建表 |
| `data/repository/ExposedUserRepository.kt` | 插入时写入 `passwordChangedAt` |
| `domain/usecase/RegisterUseCase.kt` | 设置 `passwordChangedAt = now` |
| `features/auth/AuthSchema.kt` | +`RefreshRequest`, +`TokenResponse`, UserResponse +`refreshToken`/`expiresIn` |
| `features/auth/AuthMappers.kt` | +新错误映射, `toResponse(TokenPair)` |
| `features/auth/AuthRoutes.kt` | 重写：使用 `RefreshTokenUseCase`, +`POST /refresh`, 移除 `/validate` |
| `core/di/DataModule.kt` | +`RefreshTokenRepository` 绑定 |
| `core/di/DomainModule.kt` | +`RefreshTokenUseCase`, +`RefreshTokenService` |
| `plugins/Routing.kt` | 更新 `authRoutes` 签名, +`sensitive {}` 示例 |
