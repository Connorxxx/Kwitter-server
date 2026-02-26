package com.connor.data.db.schema

import org.jetbrains.exposed.v1.core.Table
// 如需 datetime 列类型，可添加 Exposed 的 time 模块依赖

object UsersTable : Table("users") {
    // 对应 Domain 的 UserId。
    // 在 DB 里我们存为 String (varchar)，长度根据你选的 ID 算法定，雪花算法通常 20 位够了
    val id = long("id")

    // 对应 Domain 的 Email。加 uniqueIndex 确保数据库层面的唯一性约束
    val email = varchar("email", 128).uniqueIndex()

    // 对应 Domain 的 PasswordHash。存字符串。
    val passwordHash = varchar("password_hash", 128)

    // 对应 Domain 的 Username。加 uniqueIndex 确保唯一性
    val username = varchar("username", 20).uniqueIndex()

    val displayName = varchar("display_name", 64)
    val bio = text("bio").default("")

    // 头像 URL (可选)
    val avatarUrl = varchar("avatar_url", 256).nullable()

    // DM 权限设置（EVERYONE / MUTUAL_FOLLOW）
    val dmPermission = varchar("dm_permission", 20).default("EVERYONE")

    // 密码修改时间（用于敏感路由校验 tokenIssuedAt < passwordChangedAt）
    val passwordChangedAt = long("password_changed_at").default(0)

    // 对应 createdAt。使用 datetime 类型
    val createdAt = long("created_at") // 或者用 datetime("created_at")

    // 必须重写 primaryKey 告诉 Exposed 主键是谁
    override val primaryKey = PrimaryKey(id)
}
