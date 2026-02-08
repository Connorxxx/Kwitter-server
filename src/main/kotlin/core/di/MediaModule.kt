package com.connor.core.di

import com.connor.data.repository.FileSystemMediaStorageRepository
import com.connor.domain.model.MediaConfig
import com.connor.domain.repository.MediaStorageRepository
import com.connor.domain.usecase.UploadMediaUseCase
import io.ktor.server.application.Application
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.ktor.ext.get

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
}
