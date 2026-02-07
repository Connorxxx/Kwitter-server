# 日志系统使用指南

## 📋 概述

项目已配置完善的日志系统，用于排查问题和监控应用运行状态。

## 🎯 日志级别配置

### logback.xml 配置

位置: `src/main/resources/logback.xml`

```xml
<!-- 应用日志（最详细） -->
<logger name="com.connor" level="DEBUG"/>

<!-- HTTP 请求日志 -->
<logger name="io.ktor.server.application" level="DEBUG"/>

<!-- 数据库 SQL 日志 -->
<logger name="Exposed" level="DEBUG"/>
<logger name="org.jetbrains.exposed.sql.statements" level="DEBUG"/>
```

**级别说明**：
- `DEBUG`: 开发环境，记录详细信息
- `INFO`: 生产环境，记录关键操作
- `WARN`: 警告信息（如业务错误）
- `ERROR`: 严重错误（如未捕获异常）

## 📝 日志输出示例

### 1. 注册成功的完整日志链路

```
2025-02-07 15:30:45.123 INFO  [ktor-server-thread] AuthRoutes - 收到注册请求: email=user@example.com, displayName=张三, clientIp=192.168.1.100, userAgent=okhttp/4.9.0

2025-02-07 15:30:45.124 INFO  [ktor-server-thread] RegisterUseCase - 开始注册流程: email=user@example.com, displayName=张三

2025-02-07 15:30:45.125 DEBUG [ktor-server-thread] RegisterUseCase - 邮箱格式验证通过: user@example.com

2025-02-07 15:30:45.126 DEBUG [ktor-server-thread] RegisterUseCase - 密码强度验证通过

2025-02-07 15:30:45.127 DEBUG [ktor-server-thread] RegisterUseCase - 用户实体创建成功: userId=550e8400-e29b-41d4-a716-446655440000

2025-02-07 15:30:45.128 DEBUG [ktor-server-thread] ExposedUserRepository - 准备插入用户: userId=550e8400-e29b-41d4-a716-446655440000, email=user@example.com

2025-02-07 15:30:45.150 DEBUG [ktor-server-thread] Exposed - INSERT INTO users (id, email, password_hash, display_name, bio, created_at) VALUES (?, ?, ?, ?, ?, ?)

2025-02-07 15:30:45.165 INFO  [ktor-server-thread] ExposedUserRepository - 用户插入成功: userId=550e8400-e29b-41d4-a716-446655440000, email=user@example.com

2025-02-07 15:30:45.166 INFO  [ktor-server-thread] RegisterUseCase - 注册成功: userId=550e8400-e29b-41d4-a716-446655440000, email=user@example.com

2025-02-07 15:30:45.180 INFO  [ktor-server-thread] AuthRoutes - 注册成功: userId=550e8400-e29b-41d4-a716-446655440000, email=user@example.com, duration=57ms, clientIp=192.168.1.100

2025-02-07 15:30:45.185 INFO  [ktor-server-thread] CallLogging - POST /v1/auth/register -> 201 | Client: 192.168.1.100 | UA: okhttp/4.9.0
```

### 2. 注册失败的日志链路

#### 邮箱格式错误

```
2025-02-07 15:31:10.123 INFO  [ktor-server-thread] AuthRoutes - 收到注册请求: email=invalid-email, displayName=张三, clientIp=192.168.1.100, userAgent=Ktor client

2025-02-07 15:31:10.124 INFO  [ktor-server-thread] RegisterUseCase - 开始注册流程: email=invalid-email, displayName=张三

2025-02-07 15:31:10.125 WARN  [ktor-server-thread] RegisterUseCase - 邮箱格式验证失败: email=invalid-email, error=AuthError$InvalidEmail(value=invalid-email)

2025-02-07 15:31:10.126 WARN  [ktor-server-thread] AuthRoutes - 注册失败: email=invalid-email, error=InvalidEmail, errorCode=INVALID_EMAIL, statusCode=400, duration=3ms, clientIp=192.168.1.100

2025-02-07 15:31:10.127 INFO  [ktor-server-thread] CallLogging - POST /v1/auth/register -> 400 | Client: 192.168.1.100 | UA: Ktor client
```

#### 密码强度不足

```
2025-02-07 15:32:20.123 INFO  [ktor-server-thread] AuthRoutes - 收到注册请求: email=user@example.com, displayName=张三, clientIp=192.168.1.100, userAgent=Ktor client

2025-02-07 15:32:20.124 INFO  [ktor-server-thread] RegisterUseCase - 开始注册流程: email=user@example.com, displayName=张三

2025-02-07 15:32:20.125 DEBUG [ktor-server-thread] RegisterUseCase - 邮箱格式验证通过: user@example.com

2025-02-07 15:32:20.126 WARN  [ktor-server-thread] RegisterUseCase - 密码强度验证失败: email=user@example.com, error=AuthError$WeakPassword(reason=密码至少需要 8 位字符)

2025-02-07 15:32:20.127 WARN  [ktor-server-thread] AuthRoutes - 注册失败: email=user@example.com, error=WeakPassword, errorCode=WEAK_PASSWORD, statusCode=400, duration=4ms, clientIp=192.168.1.100

2025-02-07 15:32:20.128 INFO  [ktor-server-thread] CallLogging - POST /v1/auth/register -> 400 | Client: 192.168.1.100 | UA: Ktor client
```

#### 邮箱已存在

```
2025-02-07 15:33:30.123 INFO  [ktor-server-thread] AuthRoutes - 收到注册请求: email=existing@example.com, displayName=李四, clientIp=192.168.1.100, userAgent=Ktor client

2025-02-07 15:33:30.124 INFO  [ktor-server-thread] RegisterUseCase - 开始注册流程: email=existing@example.com, displayName=李四

2025-02-07 15:33:30.125 DEBUG [ktor-server-thread] RegisterUseCase - 邮箱格式验证通过: existing@example.com

2025-02-07 15:33:30.126 DEBUG [ktor-server-thread] RegisterUseCase - 密码强度验证通过

2025-02-07 15:33:30.127 DEBUG [ktor-server-thread] RegisterUseCase - 用户实体创建成功: userId=660e8400-e29b-41d4-a716-446655440000

2025-02-07 15:33:30.128 DEBUG [ktor-server-thread] ExposedUserRepository - 准备插入用户: userId=660e8400-e29b-41d4-a716-446655440000, email=existing@example.com

2025-02-07 15:33:30.145 ERROR [ktor-server-thread] ExposedUserRepository - 数据库错误: sqlState=23505, message=duplicate key value violates unique constraint "users_email_key", email=existing@example.com

2025-02-07 15:33:30.146 WARN  [ktor-server-thread] ExposedUserRepository - 邮箱已存在（PostgreSQL）: email=existing@example.com

2025-02-07 15:33:30.147 ERROR [ktor-server-thread] RegisterUseCase - 用户保存失败: email=existing@example.com, error=AuthError$UserAlreadyExists(email=existing@example.com)

2025-02-07 15:33:30.148 WARN  [ktor-server-thread] AuthRoutes - 注册失败: email=existing@example.com, error=UserAlreadyExists, errorCode=USER_EXISTS, statusCode=409, duration=25ms, clientIp=192.168.1.100

2025-02-07 15:33:30.149 INFO  [ktor-server-thread] CallLogging - POST /v1/auth/register -> 409 | Client: 192.168.1.100 | UA: Ktor client
```

### 3. JSON 格式错误

```
2025-02-07 15:34:40.123 WARN  [ktor-server-thread] StatusPages - JSON 反序列化错误: path=/v1/auth/register, clientIp=192.168.1.100, error=Unexpected JSON token at offset 15: Expected quotation mark '"'

2025-02-07 15:34:40.125 INFO  [ktor-server-thread] CallLogging - POST /v1/auth/register -> 400 | Client: 192.168.1.100 | UA: curl/7.79.1
```

### 4. 路径不存在

```
2025-02-07 15:35:50.123 WARN  [ktor-server-thread] StatusPages - 路径不存在: path=/api/register, clientIp=192.168.1.100

2025-02-07 15:35:50.124 INFO  [ktor-server-thread] CallLogging - POST /api/register -> 404 | Client: 192.168.1.100 | UA: curl/7.79.1
```

## 🔍 排查问题的关键信息

每个请求的日志包含：

1. **时间戳**: 精确到毫秒
2. **客户端 IP**: 方便追踪来源
3. **User-Agent**: 识别客户端类型（Android/iOS/Web）
4. **请求耗时**: `duration=XXms`
5. **错误类型**: `error=InvalidEmail`
6. **错误码**: `errorCode=INVALID_EMAIL`
7. **HTTP 状态码**: `statusCode=400`

## 🛠 排查步骤

### 1. 客户端报错时

1. 记录客户端收到的**错误响应体**
2. 记录**请求发送时间**（精确到秒）
3. 在服务端日志中搜索：
   ```bash
   grep "15:30:45" application.log
   ```
4. 查找包含 `clientIp` 或 `email` 的完整日志链路

### 2. 数据库相关错误

查找 `ExposedUserRepository` 或 `Exposed` 日志：
```bash
grep "ExposedUserRepository" application.log
grep "sqlState=" application.log
```

### 3. 验证相关错误

查找 `RegisterUseCase` 的 WARN 级别日志：
```bash
grep "WARN.*RegisterUseCase" application.log
```

## 📊 性能监控

每个请求都会记录耗时 (`duration=XXms`)，可以统计：

```bash
# 查找慢请求（>100ms）
grep "duration=" application.log | awk -F'duration=' '{print $2}' | awk -F'ms' '{if($1>100) print}'
```

## 🔒 安全性

**注意**: 日志中**不会**记录：
- 明文密码
- JWT Token 完整内容
- 敏感的个人信息

**会记录**：
- 邮箱地址（用于排查）
- 用户 ID
- 客户端 IP
- 错误详情

## 💡 生产环境建议

1. **调整日志级别**:
   ```xml
   <logger name="com.connor" level="INFO"/>  <!-- 从 DEBUG 改为 INFO -->
   ```

2. **启用文件日志**:
   ```xml
   <root level="INFO">
       <appender-ref ref="STDOUT"/>
       <appender-ref ref="FILE"/>  <!-- 取消注释 -->
   </root>
   ```

3. **日志收集**: 使用 ELK (Elasticsearch + Logstash + Kibana) 或 Grafana Loki

4. **日志告警**: 监控 ERROR 级别日志，自动发送通知

## 📱 客户端对接建议

### Android/iOS 客户端应记录

```kotlin
// 发送请求前
log.d("Register request: email=$email, timestamp=${System.currentTimeMillis()}")

// 收到响应后
log.d("Register response: code=${response.code}, body=$body, duration=${duration}ms")

// 发生错误时
log.e("Register failed: errorCode=$errorCode, message=$message")
```

### 向后端报告问题时提供

1. **客户端日志**（包含时间戳）
2. **设备信息**（Android/iOS 版本、设备型号）
3. **网络环境**（WiFi/4G/5G）
4. **复现步骤**

后端根据**时间戳**和 **clientIp** 快速定位问题。

---

## ✅ 总结

日志系统已完整配置：

- ✅ **Transport 层**: 记录 HTTP 请求/响应
- ✅ **UseCase 层**: 记录业务流程和错误
- ✅ **Repository 层**: 记录数据库操作和 SQL 错误
- ✅ **全局异常**: 捕获所有未处理异常
- ✅ **结构化输出**: 便于搜索和分析

现在可以通过日志快速定位 Android 和 iOS 客户端的注册问题！
