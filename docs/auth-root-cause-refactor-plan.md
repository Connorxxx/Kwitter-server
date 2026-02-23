# Auth 认证系统根因级重构修复方案

**更新时间**: 2026-02-12  
**适用版本假设**: Ktor `3.4.0`、Exposed `1.0.0`（以仓库 `build.gradle.kts` / `gradle.properties` 为准）  
**目标读者**: 后端开发同学、联调同学、评审同学  
**文档定位**: 不是补丁说明，而是架构重构方案（从源头消除问题）

---

## 1. 执行摘要

当前 Auth 刷新链路存在两类真实结构性问题：

1. Refresh Token 宽限期判断依赖错误时间语义（把 `createdAt` 当成“撤销时间”用）。  
2. Refresh Token 轮换是多步骤非原子流程（`find -> revoke -> issue`），并发下一致性不可证明。

同时存在一个可用性设计冲突：

3. 公开路由使用 `authenticate(optional = true)` 时，携带脏 token 会触发 challenge 返回 401，导致公开接口可用性下降。

StatusPages “先 Throwable 再具体异常会吞没细粒度异常”在 Ktor 3.4.0 下不成立，但当前代码可读性仍可优化。

**本方案的核心决策**：

- 不做局部修补，不在原有流程上继续叠加 if/else。
- 将 Refresh 流程重构为“显式状态机 + 单事务状态迁移”。
- 将“公开路由取当前用户”与“受保护路由强鉴权”解耦成两条明确通道。

---

## 2. 设计哲学对齐（必须遵守）

本次重构必须满足以下原则：

1. `Make illegal states unrepresentable`：用类型和状态机约束非法状态。  
2. 业务不变量必须由边界承载：Repository Port 提供“原子业务命令”，而不是拼装式 CRUD。  
3. 不以“补偿逻辑”掩盖模型缺陷：拒绝在 UseCase 继续堆叠防御分支。  
4. 公开接口可用性优先：无效凭证不能拖垮公开读取路径。  
5. 先保证语义可证明，再谈局部性能优化。

---

## 3. 问题定性（结论）

### 3.1 Refresh 宽限期误判（真实问题）

- 现状：`timeSinceRevoked` 用 `createdAt` 计算。  
- 影响：token 只要创建时间较久，几乎必然越过宽限期，误触发 family revoke。  
- 本质：数据模型缺失 `revokedAt`，导致语义错配。

### 3.2 Refresh 非原子轮换（真实问题）

- 现状：读取、撤销、签发分步执行，跨多个 repository 调用。  
- 影响：并发刷新下可能同时通过检查并签发多份新 token。  
- 本质：Port 设计错误，暴露了“步骤”而不是“事务性意图”。

### 3.3 optional + challenge 导致公开接口 401（真实行为）

- 现状：公开路由挂可选鉴权，provider 设置 challenge。  
- 行为：请求带无效 token 时会走 challenge。  
- 本质：把“公开读取附带身份上下文”错误地建模为“弱化版强鉴权”。

### 3.4 StatusPages 吞没细粒度异常（误判）

- 在 Ktor 3.4.0 中，StatusPages 会按最近父类匹配异常处理器，而非简单按注册顺序覆盖。  
- 结论：该问题不是当前高优先级缺陷，但应提升代码可读性，避免团队误解。

---

## 4. 目标架构（重构后）

## 4.1 领域不变量

重构后必须强制满足：

1. 一个 token 的撤销时间必须可追踪（`revoked_at` 不可缺失）。  
2. 一个 family 在任意时刻只能有一个“当前可用 token”。  
3. 刷新是单个原子命令，不能拆成“读+改+写”跨事务组合。  
4. 已轮换 token 的再使用必须区分“并发陈旧请求”和“可疑重放”。  
5. 公开路由对无效 token 必须降级为匿名，而不是 401。

## 4.2 领域模型重构

建议新增领域类型（示意）：

```kotlin
sealed interface RefreshTokenState {
    data object Active : RefreshTokenState
    data class Rotated(val revokedAt: Long, val rotatedToTokenId: String) : RefreshTokenState
    data class FamilyRevoked(val revokedAt: Long, val reason: RevocationReason) : RefreshTokenState
}

enum class RevocationReason {
    ROTATION,
    REUSE_ATTACK,
    USER_LOGOUT,
    PASSWORD_CHANGED,
    ADMIN_FORCE
}
```

UseCase 不再手写“猜测式流程”，只消费原子命令结果：

```kotlin
sealed interface RotateRefreshOutcome {
    data class Rotated(val userId: UserId, val familyId: String) : RotateRefreshOutcome
    data class StaleWithinGrace(val userId: UserId, val familyId: String) : RotateRefreshOutcome
    data class ReuseDetected(val userId: UserId, val familyId: String) : RotateRefreshOutcome
    data object TokenExpired : RotateRefreshOutcome
    data object TokenNotFound : RotateRefreshOutcome
}
```

## 4.3 Repository Port 重构

将当前“步骤式接口”替换为“意图式原子命令”：

```kotlin
interface RefreshTokenRepository {
    suspend fun rotateAtomically(command: RotateRefreshCommand): RotateRefreshOutcome
    suspend fun createInitialSession(command: CreateSessionCommand): Unit
    suspend fun revokeFamily(command: RevokeFamilyCommand): Unit
}
```

说明：

- `rotateAtomically` 必须在一次数据库事务内完成校验、状态迁移、新 token 落库。  
- UseCase 不再分别调用 `findByTokenHash + revokeByTokenHash + save`。  
- Port 直接承载不变量，减少调用方可犯错空间。

---

## 5. 数据库重构方案

## 5.1 `refresh_tokens` 结构调整

新增字段（建议）：

- `status`：`ACTIVE | ROTATED | FAMILY_REVOKED | EXPIRED`  
- `revoked_at`：BIGINT，可空  
- `revocation_reason`：VARCHAR(32)，可空  
- `rotated_to_token_id`：VARCHAR(36)，可空  
- `version`：BIGINT，不可空（family 内单调递增）

索引约束（建议）：

1. `UNIQUE(token_hash)`  
2. `UNIQUE(family_id, version)`  
3. `INDEX(family_id, status)`  
4. `INDEX(user_id, status)`

## 5.2 可选：引入 `refresh_token_families` 表（推荐）

```sql
CREATE TABLE refresh_token_families (
  family_id          VARCHAR(36) PRIMARY KEY,
  user_id            VARCHAR(36) NOT NULL,
  status             VARCHAR(16) NOT NULL,      -- ACTIVE / REVOKED
  current_version    BIGINT NOT NULL,
  revoked_at         BIGINT,
  created_at         BIGINT NOT NULL,
  updated_at         BIGINT NOT NULL
);
```

价值：

- 将“family 当前状态”从 token 明细中提炼为一等概念。  
- 并发刷新时可通过 family 行锁收敛竞争域，降低状态分叉风险。  
- family 级撤销有稳定落点，不依赖批量 update 推断状态。

---

## 6. 刷新协议重定义（重点）

## 6.1 旧协议问题

旧协议把“10 秒宽限”定义为“可再次签发新 token”，这会引入多活 token 与状态漂移。

## 6.2 新协议（建议）

### 正常刷新

- 条件：token `ACTIVE` 且未过期。  
- 结果：原子迁移为 `ROTATED`（写入 `revoked_at`），生成下一个版本 token（唯一 `ACTIVE`）。

### 并发陈旧请求（宽限期内）

- 条件：token 已 `ROTATED` 且 `now - revoked_at <= grace`。  
- 结果：返回 `STALE_REFRESH_TOKEN`（非攻击），**不签发新 token，不撤销 family**。  
- 客户端动作：重新加载本地最新 token 并重试一次原请求。

### 可疑重放（宽限期外）

- 条件：token 已 `ROTATED` 且超过宽限期。  
- 结果：判定 `TOKEN_REUSE_DETECTED`，撤销 family + 推送 WebSocket 强制下线。

## 6.3 为什么改为 `STALE_REFRESH_TOKEN`

> **✅ 已采纳实施**（2026-02-24）：grace period 内不再签发新 token pair，改为返回 `StaleRefreshToken` 错误（HTTP 409）。
> 见 `RefreshTokenUseCase.handlePossibleReuse()` 和 `AuthMappers.kt` 中的 409 映射。

这是本次去熵增的关键决策：

1. 保持"单 family 单 active token"不变量。
2. 避免宽限期内重复签发导致的 token 分叉。
3. 并发语义可解释，客户端可实现确定性恢复。

---

## 7. 公开路由鉴权重构

## 7.1 问题根因

`authenticate(optional = true)` 仍属于 Authentication 插件路径，遇到无效凭证会进入失败分支，不适合作为“公开路由仅尝试拿 principal”机制。

## 7.2 目标方案

拆成两条通道：

1. **强鉴权通道**：`authenticate("auth-jwt-required")`，失败即 401。  
2. **软鉴权通道**：公开路由不挂 `authenticate`，改为 `call.tryResolvePrincipal()`：
   - 无 token -> `null`
   - token 无效/过期 -> `null`（记录 debug 日志）
   - token 有效 -> `UserPrincipal`

## 7.3 设计收益

1. 公开接口可用性稳定，不被脏 token 拖垮。  
2. “身份增强”与“访问控制”职责分离。  
3. 领域逻辑只关心 `currentUserId: UserId?`，不关心框架挑战机制。

---

## 8. StatusPages 处理策略

## 8.1 结论

- Ktor 3.4.0 会匹配最近父类异常处理器。  
- `SerializationException` 不会因为注册在 `Throwable` 后面而天然失效。

## 8.2 重构动作

1. 代码顺序改为“具体异常在前，通用异常在后”（提升可读性）。  
2. 在注释中写明“匹配策略由 Ktor 最近父类选择，不依赖注册先后”。  
3. 增加集成测试固定行为，避免团队后续误判。

---

## 9. API 契约变更

新增错误码：

- `STALE_REFRESH_TOKEN`（建议 HTTP 409）

语义：

- 非安全攻击，不应触发“异常登录”提示。  
- 表示本次 refresh 使用的是已被并发请求轮换过的旧 token。  
- 客户端应重新读取本地最新 token 后重试。

保持不变：

- `REFRESH_TOKEN_INVALID`  
- `REFRESH_TOKEN_EXPIRED`  
- `TOKEN_REUSE_DETECTED`（安全事件）

---

## 10. 落地实施计划（按迭代）

## 10.1 迭代 A：数据模型与 Port 重构（必须）

1. 增加迁移脚本：新字段与索引。  
2. Repository Port 改为 `rotateAtomically`。  
3. Exposed 实现单事务状态迁移。  
4. UseCase 改为消费 `RotateRefreshOutcome`，删除步骤式调用。

完成标准：

- 代码中不再出现 `find + revoke + issue` 拼接流程。  
- `revoked_at` 必填于所有撤销路径。  
- 并发刷新不会产生多活 token。

## 10.2 迭代 B：路由鉴权解耦（必须）

1. 新增 `auth-jwt-required` provider（强鉴权）。  
2. 公开路由移除 `authenticateOptional`。  
3. 新增 `tryResolvePrincipal()`（软鉴权）并统一复用。

完成标准：

- 公开路由携带脏 token 时仍返回业务数据（匿名视角）。  
- 受保护路由维持严格 401 行为。

## 10.3 迭代 C：错误协议与客户端对齐（必须）

1. 增加 `STALE_REFRESH_TOKEN` 映射。  
2. 客户端接入“重读 token + 一次重试”策略。  
3. 更新联调文档与测试用例。

完成标准：

- 并发刷新场景可稳定恢复，不再误判为攻击。  
- 安全事件与并发陈旧事件有清晰区分。

## 10.4 迭代 D：可观测性与清理（建议）

1. 统一刷新链路日志字段：`familyId`、`tokenVersion`、`revocationReason`、`correlationId`。  
2. 增加过期清理任务对 `status` / `revoked_at` 维度的清理策略。  
3. 新增审计查询脚本，支持排查 reuse 事件。

---

## 11. 测试与验收清单

必须覆盖：

1. 正常刷新：单请求成功，旧 token 失效，新 token 生效。  
2. 并发刷新：一个成功，其他返回 `STALE_REFRESH_TOKEN`，family 不撤销。  
3. 宽限期外旧 token 重放：`TOKEN_REUSE_DETECTED` + family 撤销 + WebSocket 推送。  
4. 公开路由携带过期 token：返回 200（匿名视角）而不是 401。  
5. 受保护路由携带过期 token：返回 401。  
6. StatusPages：`SerializationException` 命中细粒度处理器，`Throwable` 处理兜底异常。

---

## 12. 明确不做（防止再次熵增）

1. 不在现有 `find/revoke/issue` 上继续叠加条件分支。  
2. 不把并发问题推给“扩大宽限期”这类参数调优。  
3. 不在公开路由继续复用 `authenticate(optional = true)` 来“碰运气”。  
4. 不通过吞异常或模糊错误码掩盖状态机缺陷。

---

## 13. 参考资料

Ktor 3.4.0 认证可选行为（optional 仅在无凭证时抑制 challenge）：

- https://raw.githubusercontent.com/ktorio/ktor-documentation/main/topics/whats-new-340.md
- https://raw.githubusercontent.com/ktorio/ktor/3.4.0/ktor-server/ktor-server-plugins/ktor-server-auth/common/src/io/ktor/server/auth/AuthenticationInterceptors.kt

Ktor 3.4.0 StatusPages 异常匹配机制（最近父类选择）：

- https://raw.githubusercontent.com/ktorio/ktor/3.4.0/ktor-server/ktor-server-plugins/ktor-server-status-pages/common/src/io/ktor/server/plugins/statuspages/StatusPages.kt

---

## 14. 一句话原则（给开发同学）

不要修补旧流程；把刷新链路重写为“可证明正确的状态迁移系统”，让正确性来自模型和边界，而不是来自运气和补偿逻辑。
