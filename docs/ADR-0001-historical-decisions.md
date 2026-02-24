# ADR-0001: 历史迁移决策归档（Auth + User Profile）

**状态**: Accepted  
**日期**: 2026-02-24  
**适用范围**: Auth 刷新链路、User Profile 领域模型与集成边界  
**目标**: 将历史/迁移文档收敛为单一 ADR，只保留已采纳的决策结论

---

## 背景

此前 `auth-root-cause-refactor-plan.md` 与 `user-profile-implementation.md` 承载了大量过程性内容（评审过程、分步实施、排障说明）。随着实现落地，这些过程信息已不再作为主入口，需保留的只有可持续约束系统行为的决策结论。

---

## 决策结论

### D1. Auth Refresh 采用“原子轮换 + 显式状态语义”

1. Refresh 轮换语义必须可证明，禁止继续使用 `find -> revoke -> issue` 的拼接流程。
2. 并发陈旧请求在宽限期内返回 `STALE_REFRESH_TOKEN`（HTTP 409），不再签发新 token pair。
3. 宽限期外旧 token 重放判定为 `TOKEN_REUSE_DETECTED`，执行 family 级撤销与强制下线。
4. 刷新链路必须具备可追踪撤销时间语义（`revoked_at`），避免时间语义误判。

### D2. Auth 鉴权通道拆分为“强鉴权/软鉴权”

1. 受保护路由使用强鉴权 provider，凭证无效即 401。
2. 公开路由不依赖 `authenticate(optional = true)` 承载业务可用性。
3. 公开路由对无效或过期 token 降级为匿名视角，而非挑战失败。

### D3. User Profile 领域模型以类型边界为准

1. `username` 为规范化（小写）且全局唯一的领域字段。
2. `displayName`、`bio` 采用值对象约束，禁止无边界 `String` 直通核心域模型。
3. Follow 关系明确禁止“关注自己”。

### D4. User Profile 数据模型与查询边界

1. `follows` 采用 `(follower_id, following_id)` 复合主键，并保留双向索引以覆盖关注/粉丝查询路径。
2. 列表交互状态查询采用批量方法，避免 N+1。
3. 分页与查询策略在保持现有可用性的前提下演进，性能优化优先级高于额外功能扩展。

---

## 结果与影响

1. 历史文档从“主导航”移除，避免团队继续基于过程稿做新开发决策。
2. 所有后续实现与联调以当前有效设计/实现文档为准，本 ADR 仅作为历史决策锚点。
3. 若未来发生架构级反向调整，新增 ADR 记录，不恢复过程性文档为主入口。

---

## 替代与归档

本 ADR 替代以下历史文档中的决策结论：

- `auth-root-cause-refactor-plan.md`
- `user-profile-implementation.md`
