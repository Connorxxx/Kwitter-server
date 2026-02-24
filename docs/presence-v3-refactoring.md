# Presence v3 重构说明（服务端 → 客户端）

- 日期：2026-02-23
- 关联文档：`docs/messaging-client-integration-guide.md` 第 9.6 节

---

## 1. 重构动机

v2.1 的 presence 实现存在架构层面的问题，补丁式修复无法根治：

| 问题 | 根因 | 影响 |
|------|------|------|
| A 先连 B 后连，B 看不到 A 在线 | 快照有条件发送（`peerIds.isNotEmpty()`），不保证每次连接都有 | 客户端无法确定初始化是否完成 |
| 重连后在线状态对调 | `user_presence_changed` 广播给所有在线用户（包括无关用户），且每次设备连接都广播 | 语义不清、带宽浪费 |
| Presence 穿越领域层 | `UserPresenceChanged` 作为 `NotificationEvent` 走 `NotificationRepository` | 连接状态不是业务事件，架构混乱 |

## 2. 核心设计变更

### 2.1 Presence 回归连接层

Presence 不再经过 `NotificationRepository`/`NotificationEvent`，直接由 `NotificationWebSocket` 在连接生命周期内处理。

**移除**：
- `NotificationEvent.UserPresenceChanged` data class
- `NotificationRepository.notifyUserPresenceChanged()` 方法
- `InMemoryNotificationRepository.notifyUserPresenceChanged()` 实现

**新增**：
- `WebSocketConnectionManager.sendToUsers(userIds, message)` — 定向多用户推送

### 2.2 协议语义强化

| 事件 | v2.1 行为 | v3 行为 |
|------|----------|---------|
| `presence_snapshot` | 有会话对端时才发 | **每次连接必发**（即使 `users=[]`） |
| `user_presence_changed` 上线 | 广播给所有在线用户，每次设备连接都触发 | **仅推送给对话对端**，仅首个会话(0→1)触发 |
| `user_presence_changed` 下线 | 广播给所有在线用户 | **仅推送给对话对端**，仅最后会话(1→0)触发 |

### 2.3 连接时序（不变，但保证更强）

```
1. connected                                ← 连接确认
2. presence_snapshot                         ← 必发，允许 users=[]
3. user_presence_changed(isOnline=true)      ← 仅首次上线，仅推给对话对端
```

## 3. 线上格式

**线上 JSON 格式完全不变**，客户端无需修改解析逻辑。

### presence_snapshot

```json
{
  "type": "presence_snapshot",
  "data": {
    "users": [
      { "userId": "u1", "isOnline": true, "timestamp": 1707600000000 },
      { "userId": "u2", "isOnline": false, "timestamp": 1707600000000 }
    ]
  }
}
```

### user_presence_changed

```json
{
  "type": "user_presence_changed",
  "data": {
    "userId": "u1",
    "isOnline": true,
    "timestamp": 1707600000000
  }
}
```

## 4. 客户端建议改动

### 4.1 快照处理：clear 再写入

收到 `presence_snapshot` 时，**先清空旧状态再写入**（而非仅追加）。重连场景下旧数据可能已过期。

```kotlin
// Before (v2.1)
fun handlePresenceSnapshot(users: List<PresenceUser>) {
    users.forEach { _onlineUsers[it.userId] = it.isOnline }
}

// After (v3)
fun handlePresenceSnapshot(users: List<PresenceUser>) {
    _onlineUsers.clear()
    users.forEach { _onlineUsers[it.userId] = it.isOnline }
}
```

### 4.2 不再需要的防御代码

由于 `presence_snapshot` 保证必发，以下防御逻辑可移除：

- "未收到快照时的 fallback 逻辑"
- "快照超时重试"
- "连接后主动拉取在线状态的 HTTP 请求"

### 4.3 无需修改的部分

- `user_presence_changed` 的解析和处理逻辑 — 格式不变
- 心跳（ping/pong）逻辑 — 不变
- 打字状态（typing_indicator）逻辑 — 不变

## 5. 验收矩阵

| # | 场景 | 预期 |
|---|------|------|
| 1 | A 先连，B 后连 | A/B 均看到对方在线 |
| 2 | B 先连，A 后连 | A/B 均看到对方在线 |
| 3 | A 重连 10 次 | B 侧在线状态不抖动 |
| 4 | A 多设备同时在线 | B 仅在 A 首次上线时收到通知 |
| 5 | A 多设备逐个断开 | B 仅在 A 最后一个设备断开时收到下线 |
| 6 | A/B 无对话记录 | 互不收到对方的在线状态（正确行为） |
| 7 | A 连接时 DB 查询失败 | A 收到空快照 `users=[]`，不崩溃 |
