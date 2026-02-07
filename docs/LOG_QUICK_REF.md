# 日志快速参考

## 🚀 快速开始

### 查看实时日志

```bash
# 启动服务器
./gradlew run

# 或查看历史日志文件（如果启用）
tail -f logs/application.log
```

## 🔍 常见问题排查

### 1. 客户端注册失败

**步骤 1**: 客户端提供以下信息
- 注册失败的时间（例如：15:30:45）
- 使用的邮箱地址
- 错误信息

**步骤 2**: 在服务端日志中搜索

```bash
# 方法 1: 搜索时间
grep "15:30:45" 日志输出

# 方法 2: 搜索邮箱
grep "email=user@example.com" 日志输出

# 方法 3: 搜索客户端 IP
grep "clientIp=192.168.1.100" 日志输出
```

**步骤 3**: 查看完整的日志链路

从第一条 `AuthRoutes - 收到注册请求` 开始，一直到最后一条 `CallLogging`，完整的请求处理链路都会被记录。

### 2. 常见错误及原因

#### ❌ `INVALID_EMAIL` (400 Bad Request)

**日志示例**:
```
WARN RegisterUseCase - 邮箱格式验证失败: email=invalid-email
WARN AuthRoutes - 注册失败: error=InvalidEmail, errorCode=INVALID_EMAIL
```

**原因**: 邮箱格式不符合规范
**解决**: 检查客户端邮箱验证逻辑

---

#### ❌ `WEAK_PASSWORD` (400 Bad Request)

**日志示例**:
```
WARN RegisterUseCase - 密码强度验证失败: error=WeakPassword(reason=密码至少需要 8 位字符)
```

**原因**: 密码不符合强度要求
**密码规则**:
- 至少 8 位字符
- 不超过 72 位（BCrypt 限制）
- 必须包含数字
- 必须包含字母

**解决**: 提示用户设置更强的密码

---

#### ❌ `USER_EXISTS` (409 Conflict)

**日志示例**:
```
ERROR ExposedUserRepository - 数据库错误: sqlState=23505, message=duplicate key
WARN ExposedUserRepository - 邮箱已存在: email=existing@example.com
WARN AuthRoutes - 注册失败: error=UserAlreadyExists, errorCode=USER_EXISTS
```

**原因**: 邮箱已被注册
**解决**: 提示用户该邮箱已被使用，或引导用户登录

---

#### ❌ `INVALID_JSON` (400 Bad Request)

**日志示例**:
```
WARN StatusPages - JSON 反序列化错误: error=Unexpected JSON token at offset 15
```

**原因**: JSON 格式错误或 Content-Type 不是 `application/json`

**检查清单**:
```kotlin
// ✅ 正确的请求
POST /v1/auth/register HTTP/1.1
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "password123",
  "displayName": "张三"
}
```

```kotlin
// ❌ 错误示例 1: 缺少 Content-Type
POST /v1/auth/register HTTP/1.1

{...}

// ❌ 错误示例 2: JSON 格式错误
{
  email: "user@example.com",  // 缺少引号
  "password": "password123"
}

// ❌ 错误示例 3: 字段名错误
{
  "emailAddress": "user@example.com",  // 应该是 "email"
  "pwd": "password123"                  // 应该是 "password"
}
```

---

#### ❌ `NOT_FOUND` (404 Not Found)

**日志示例**:
```
WARN StatusPages - 路径不存在: path=/api/register
```

**原因**: 请求路径错误

**正确路径**: `POST /v1/auth/register`

**常见错误**:
- ❌ `/api/register`
- ❌ `/auth/register`
- ❌ `/register`
- ✅ `/v1/auth/register`

---

#### ❌ `METHOD_NOT_ALLOWED` (405)

**日志示例**:
```
WARN StatusPages - 不支持的 HTTP 方法: method=GET, path=/v1/auth/register
```

**原因**: HTTP 方法错误

**正确方法**: `POST /v1/auth/register`

---

#### ❌ `INTERNAL_ERROR` (500 Internal Server Error)

**日志示例**:
```
ERROR StatusPages - 未捕获异常: method=POST, path=/v1/auth/register, error=NullPointerException
```

**原因**: 服务器内部错误，需要开发者修复

**排查步骤**:
1. 查看完整的堆栈跟踪
2. 检查是否有数据库连接问题
3. 检查是否有配置缺失

## 📊 性能分析

### 查看请求耗时

每个请求都会记录 `duration=XXms`，正常情况下：

- ✅ **< 50ms**: 非常快（大部分验证错误）
- ✅ **50-200ms**: 正常（包含数据库操作）
- ⚠️ **200-500ms**: 较慢（需要优化）
- ❌ **> 500ms**: 很慢（严重性能问题）

### 统计平均耗时

```bash
# 提取所有注册请求的耗时
grep "注册成功" logs/application.log | grep -oP 'duration=\K[0-9]+' | awk '{sum+=$1; count++} END {print "平均耗时:", sum/count "ms"}'
```

## 🔧 调试技巧

### 1. 启用更详细的 SQL 日志

在 `logback.xml` 中：
```xml
<logger name="Exposed" level="TRACE"/>  <!-- 从 DEBUG 改为 TRACE -->
```

### 2. 只查看错误日志

```bash
grep "ERROR\|WARN" logs/application.log
```

### 3. 过滤特定用户的日志

```bash
grep "email=user@example.com" logs/application.log
```

### 4. 查看最近 10 条日志

```bash
tail -10 logs/application.log
```

### 5. 实时监控日志

```bash
tail -f logs/application.log | grep --color "ERROR\|WARN"
```

## 📱 客户端对接检查清单

发送注册请求前，确保：

- [ ] URL 正确: `http://your-server:8080/v1/auth/register`
- [ ] HTTP 方法: `POST`
- [ ] Content-Type: `application/json`
- [ ] 请求体包含必填字段:
  ```json
  {
    "email": "user@example.com",
    "password": "password123",
    "displayName": "张三"
  }
  ```
- [ ] 邮箱格式有效
- [ ] 密码符合要求（8 位+数字+字母）

## 🎯 测试命令

### 使用 curl 测试注册

```bash
# ✅ 成功案例
curl -X POST http://localhost:8080/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123",
    "displayName": "测试用户"
  }'

# 预期响应 (201 Created):
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "test@example.com",
  "displayName": "测试用户",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

```bash
# ❌ 邮箱格式错误
curl -X POST http://localhost:8080/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "invalid-email",
    "password": "password123",
    "displayName": "测试用户"
  }'

# 预期响应 (400 Bad Request):
{
  "code": "INVALID_EMAIL",
  "message": "邮箱格式不正确: invalid-email"
}
```

```bash
# ❌ 密码太短
curl -X POST http://localhost:8080/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "123",
    "displayName": "测试用户"
  }'

# 预期响应 (400 Bad Request):
{
  "code": "WEAK_PASSWORD",
  "message": "密码至少需要 8 位字符"
}
```

## 💡 小贴士

1. **时间对齐**: 确保服务器和客户端时间一致，方便日志关联
2. **保留日志**: 开发阶段保留至少 7 天的日志
3. **定期清理**: 生产环境定期归档或删除旧日志
4. **脱敏处理**: 如果日志包含敏感信息，考虑脱敏

## 📞 遇到问题？

如果日志无法帮助定位问题：

1. 提供**完整的日志链路**（从请求开始到结束）
2. 提供**客户端的完整请求**（URL、Headers、Body）
3. 提供**客户端的错误响应**
4. 说明**复现步骤**

---

**祝排查顺利！** 🚀
