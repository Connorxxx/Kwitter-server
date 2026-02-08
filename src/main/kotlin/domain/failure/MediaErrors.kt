package com.connor.domain.failure

/**
 * 媒体功能的领域错误 - 密封接口确保编译时穷尽性检查
 */
sealed interface MediaError {
    // === 验证错误 ===
    data class InvalidFileType(val received: String, val allowed: Set<String>) : MediaError
    data class FileTooLarge(val size: Long, val maxSize: Long) : MediaError
    data class InvalidMediaUrl(val url: String) : MediaError
    data class InvalidFileName(val fileName: String) : MediaError

    // === 操作错误 ===
    data class UploadFailed(val reason: String) : MediaError
    data class DeleteFailed(val reason: String) : MediaError
    data class StorageError(val message: String) : MediaError
    data object UnsupportedOperation : MediaError
}
