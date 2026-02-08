package com.connor.features.media

import arrow.core.Either
import com.connor.core.http.ApiErrorResponse
import com.connor.domain.failure.MediaError
import com.connor.domain.model.UploadedMedia
import com.connor.domain.usecase.UploadMediaCommand
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/**
 * 判断是否为生产环境
 */
private fun isDevelopment(): Boolean {
    return System.getenv("ENVIRONMENT")?.lowercase() != "production" &&
           System.getenv("PROFILE")?.lowercase() != "prod"
}

/**
 * 根据环境返回错误详情
 * - 开发环境：包含详细信息便于调试
 * - 生产环境：仅返回通用消息
 */
private fun getErrorMessage(defaultMessage: String, detailMessage: String? = null): String {
    return if (isDevelopment()) {
        detailMessage ?: defaultMessage
    } else {
        defaultMessage
    }
}

/**
 * 媒体上传请求映射到 UseCase 命令
 */
fun createUploadMediaCommand(
    fileName: String,
    contentType: String,
    fileBytes: ByteArray
): UploadMediaCommand = UploadMediaCommand(
    fileName = fileName,
    contentType = contentType,
    fileBytes = fileBytes
)

/**
 * Domain 错误映射到 HTTP 响应
 *
 * @return Pair<HttpStatusCode, ApiErrorResponse>
 */
fun MediaError.toHttpResponse(): Pair<HttpStatusCode, ApiErrorResponse> = when (this) {
    // 验证错误 → 400 Bad Request
    is MediaError.InvalidFileType -> {
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "INVALID_FILE_TYPE",
            message = "Unsupported file type. Allowed: ${this.allowed.joinToString(", ")}"
        )
    }

    is MediaError.FileTooLarge -> {
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "FILE_TOO_LARGE",
            message = "File too large. Received: ${formatBytes(this.size)}, Max: ${formatBytes(this.maxSize)}"
        )
    }

    is MediaError.InvalidMediaUrl -> {
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "INVALID_MEDIA_URL",
            message = "Invalid media URL: ${this.url}"
        )
    }

    is MediaError.InvalidFileName -> {
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "INVALID_FILE_NAME",
            message = "Invalid file name: ${this.fileName}"
        )
    }

    // 操作错误 → 500 Internal Server Error
    is MediaError.UploadFailed -> {
        HttpStatusCode.InternalServerError to ApiErrorResponse(
            code = "UPLOAD_FAILED",
            message = "Upload failed: ${this.reason}"
        )
    }

    is MediaError.DeleteFailed -> {
        HttpStatusCode.InternalServerError to ApiErrorResponse(
            code = "DELETE_FAILED",
            message = "Delete failed: ${this.reason}"
        )
    }

    is MediaError.StorageError -> {
        HttpStatusCode.InternalServerError to ApiErrorResponse(
            code = "STORAGE_ERROR",
            message = "Storage error: ${this.message}"
        )
    }

    // 不支持的操作 → 501 Not Implemented
    MediaError.UnsupportedOperation -> {
        HttpStatusCode.NotImplemented to ApiErrorResponse(
            code = "UNSUPPORTED_OPERATION",
            message = "Operation not supported"
        )
    }
}

/**
 * 上传的媒体映射到 HTTP 响应
 */
fun UploadedMedia.toResponse(): MediaUploadResponse = MediaUploadResponse(
    url = this.url.value,
    type = this.type.name // IMAGE 或 VIDEO
)

/**
 * 格式化字节大小为可读的格式（如 5 MB）
 */
private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
