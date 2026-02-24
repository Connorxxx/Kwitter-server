# API 文档中心

本目录已完成一次文档清理：移除了过期的评审/修复过程文档，保留当前可执行的设计、实现和客户端接入文档。

## 1. 当前有效文档

### Auth（认证与会话）
- [Auth Token 系统设计](./auth-refresh-token-system.md)
- [Auth 客户端接入指南](./auth-client-integration-guide.md)

### Post（发帖与回复）
- [Post 功能设计](./post-feature-design.md)
- [Post 功能实现总结](./post-feature-implementation.md)

### Likes / Bookmarks（点赞与收藏）
- [点赞和收藏功能设计](./likes-bookmarks-design.md)

### User Profile（用户资料与关注）
- [User Profile 功能设计](./user-profile-design.md)
- [User Profile 集成完成报告](./user-profile-integration-complete.md)

### Block（拉黑与删除 Post）
- [Block 功能设计](./block-feature-design.md)
- [Block 客户端接入指南](./block-client-integration-guide.md)

### Media（媒体上传）
- [Media 功能设计](./media-feature-design.md)
- [Media 功能实现总结](./media-feature-implementation.md)

### Messaging（私信）
- [Messaging 功能设计](./messaging-feature-design.md)
- [Messaging 客户端接入指南](./messaging-client-integration-guide.md)

### Realtime Notification（实时通知）
- [Realtime Notification 功能设计](./realtime-notification-design.md)
- [Realtime Notification 实现总结](./realtime-notification-implementation-summary.md)
- [Realtime Notification 客户端示例](./realtime-notification-client-examples.md)
- [Presence v3 重构说明](./presence-v3-refactoring.md)

### Search（搜索）
- [Search 功能设计](./search-feature-design.md)
- [Search 功能实现指南](./search-feature-implementation-guide.md)

### 可观测性与运维
- [日志系统使用指南](./LOGGING.md)
- [日志快速参考](./LOG_QUICK_REF.md)

### API 页面
- [Swagger 静态页面](./index.html)

---

## 2. 架构决策归档（ADR）

- [ADR-0001: 历史迁移决策归档（Auth + User Profile）](./ADR-0001-historical-decisions.md)

说明：历史迁移文档已收敛为 ADR。后续如有架构级决策变更，新增 ADR，不恢复过程性文档为主入口。

---

## 3. 本次清理已移除文档

以下文档已过期且内容已被后续实现覆盖：

- `auth-module-refactor-review.md`
- `auth-refactor-final-decision.md`
- `post-module-refactor-review.md`
- `realtime-notification-code-review-fixes.md`
- `realtime-notification-coroutine-scope-fix.md`
- `realtime-notification-final-compilation-fixes.md`
- `auth-root-cause-refactor-plan.md`
- `user-profile-implementation.md`

---

## 4. 后续维护规则

1. 每个模块最多保留 1 份“设计文档” + 1 份“实现/集成文档”作为主入口。
2. 评审、临时修复过程、编译修复日志不再放在根目录长期保留。
3. 文档发生覆盖性变更时，直接更新主文档；历史决策统一沉淀到 ADR 文档。
4. 所有主文档需要在开头维护“更新时间”。
5. 新增文档后，同步更新本索引，避免出现孤立文档。

---

**最后更新**: 2026-02-24
