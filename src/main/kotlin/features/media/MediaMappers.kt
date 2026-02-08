package com.connor.features.media

import arrow.core.Either
import com.connor.domain.failure.MediaError
import com.connor.domain.model.UploadedMedia
import com.connor.domain.usecase.UploadMediaCommand
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/**
 * HTTP 错误响应
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val details: String? = null
)

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
 * @return Pair<HttpStatusCode, ErrorResponse>
 */
fun MediaError.toHttpResponse(): Pair<HttpStatusCode, ErrorResponse> = when (this) {
    // 验证错误 → 400 Bad Request
    is MediaError.InvalidFileType -> {
        HttpStatusCode.BadRequest to ErrorResponse(
            error = "Unsupported file type",
            details = "Received: ${this.received}. Allowed: ${this.allowed.joinToString(", ")}"
        )
    }

    is MediaError.FileTooLarge -> {
        HttpStatusCode.BadRequest to ErrorResponse(
            error = "File too large",
            details = "Received: ${formatBytes(this.size)}, Max: ${formatBytes(this.maxSize)}"
        )
    }

    is MediaError.InvalidMediaUrl -> {
        HttpStatusCode.BadRequest to ErrorResponse(
            error = "Invalid media URL",
            details = this.url
        )
    }

    is MediaError.InvalidFileName -> {
        HttpStatusCode.BadRequest to ErrorResponse(
            error = "Invalid file name",
            details = this.fileName
        )
    }

    // 操作错误 → 500 Internal Server Error
    is MediaError.UploadFailed -> {
        HttpStatusCode.InternalServerError to ErrorResponse(
            error = "Upload failed",
            details = this.reason
        )
    }

    is MediaError.DeleteFailed -> {
        HttpStatusCode.InternalServerError to ErrorResponse(
            error = "Delete failed",
            details = this.reason
        )
    }

    is MediaError.StorageError -> {
        HttpStatusCode.InternalServerError to ErrorResponse(
            error = "Storage error",
            details = this.message
        )
    }

    // 不支持的操作 → 501 Not Implemented
    MediaError.UnsupportedOperation -> {
        HttpStatusCode.NotImplemented to ErrorResponse(
            error = "Operation not supported"
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
