package com.connor.core.di

import com.connor.data.repository.FileSystemMediaStorageRepository
import com.connor.domain.model.AvatarConfig
import com.connor.domain.model.MediaConfig
import com.connor.domain.repository.MediaStorageRepository
import com.connor.domain.usecase.DeleteAvatarUseCase
import com.connor.domain.usecase.UploadAvatarUseCase
import com.connor.domain.usecase.UploadMediaUseCase
import io.ktor.server.application.Application
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 媒体功能的 Koin DI 模块
 *
 * 包括：
 * - MediaConfig（从应用配置读取）
 * - MediaStorageRepository 实现（当前为文件系统）
 * - UploadMediaUseCase（业务逻辑）
 */
fun mediaModule(app: Application) = module {
    // 1. 读取应用配置，创建 MediaConfig
    single {
        val uploadDir = app.environment.config.propertyOrNull("media.uploadDir")?.getString() ?: "uploads"
        val maxFileSizeStr = app.environment.config.propertyOrNull("media.maxFileSize")?.getString() ?: "10485760"
        val maxFileSize = maxFileSizeStr.toLongOrNull() ?: (10 * 1024 * 1024).toLong()

        val allowedTypesStr = app.environment.config.propertyOrNull("media.allowedTypes")?.getString()
            ?: "image/jpeg,image/png,image/webp,video/mp4"
        val allowedTypes = allowedTypesStr.split(",").map { it.trim() }.toSet()

        val enableDatabase = app.environment.config.propertyOrNull("media.enableDatabase")?.getString() != "false"

        MediaConfig(
            uploadDir = uploadDir,
            maxFileSize = maxFileSize,
            allowedTypes = allowedTypes,
            enableDatabase = enableDatabase
        )
    }

    // 2. 注册 MediaStorageRepository 实现
    // 当前使用文件系统实现，未来可以添加条件判断切换 S3/OSS
    single<MediaStorageRepository> {
        val config = get<MediaConfig>()
        FileSystemMediaStorageRepository(config.uploadDir)
    }

    // 3. 注册 UploadMediaUseCase
    single { UploadMediaUseCase(get(), get()) }

    // ========== Avatar 配置 ==========

    // 4. 读取 Avatar 配置
    single {
        val uploadDir = app.environment.config.propertyOrNull("avatar.uploadDir")?.getString() ?: "uploads/avatars"
        val maxFileSizeStr = app.environment.config.propertyOrNull("avatar.maxFileSize")?.getString() ?: "2097152"
        val maxFileSize = maxFileSizeStr.toLongOrNull() ?: (2 * 1024 * 1024).toLong()

        val allowedTypesStr = app.environment.config.propertyOrNull("avatar.allowedTypes")?.getString()
            ?: "image/jpeg,image/png,image/webp"
        val allowedTypes = allowedTypesStr.split(",").map { it.trim() }.toSet()

        AvatarConfig(
            uploadDir = uploadDir,
            maxFileSize = maxFileSize,
            allowedTypes = allowedTypes
        )
    }

    // 5. Avatar 专用 MediaStorageRepository
    single<MediaStorageRepository>(named("avatarStorage")) {
        val config = get<AvatarConfig>()
        FileSystemMediaStorageRepository(config.uploadDir)
    }

    // 6. Avatar Use Cases
    single { UploadAvatarUseCase(get(), get(named("avatarStorage")), get()) }
    single { DeleteAvatarUseCase(get(), get(named("avatarStorage"))) }
}
