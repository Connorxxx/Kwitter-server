package com.connor.domain.repository

import arrow.core.Either
import com.connor.domain.failure.MediaError
import com.connor.domain.model.MediaId
import com.connor.domain.model.UploadedMedia

/**
 * 媒体存储操作的接口（Port - Hexagonal Architecture）
 *
 * 实现方案：
 * - FileSystemMediaStorageRepository：本地文件系统
 * - S3MediaStorageRepository：AWS S3（未来实现）
 * - OSSMediaStorageRepository：阿里 OSS（未来实现）
 */
interface MediaStorageRepository {
    /**
     * 上传文件到存储
     *
     * @param file 文件内容的字节数组
     * @param fileName 生成的安全文件名（由 UseCase 生成）
     * @return Either<MediaError, UploadedMedia> 上传结果或错误
     */
    suspend fun upload(file: ByteArray, fileName: String): Either<MediaError, UploadedMedia>

    /**
     * 删除已上传的媒体文件
     *
     * @param mediaId 媒体ID
     * @return Either<MediaError, Unit> 删除结果或错误
     */
    suspend fun delete(mediaId: MediaId): Either<MediaError, Unit>
}
