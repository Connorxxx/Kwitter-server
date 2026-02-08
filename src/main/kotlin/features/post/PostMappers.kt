package com.connor.features.post

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.PostError
import com.connor.domain.model.*
import com.connor.domain.usecase.CreatePostCommand
import io.ktor.http.*

// ========== Request -> Domain Command ==========

/**
 * 安全解析 MediaType
 * 若解析失败返回 Either.Left，避免抛出 IllegalArgumentException
 */
fun parseMediaType(typeString: String): Either<PostError, MediaType> {
    return try {
        MediaType.valueOf(typeString).right()
    } catch (e: IllegalArgumentException) {
        PostError.InvalidMediaType(typeString).left()
    }
}

/**
 * HTTP Request -> Domain Command
 */
fun CreatePostRequest.toCommand(authorId: UserId): Either<PostError, CreatePostCommand> {
    // 先验证所有的 mediaUrls 中的 type 都是有效的
    val mediaUrlsResult = this.mediaUrls.map { media ->
        parseMediaType(media.type).map { mediaType ->
            media.url to mediaType
        }
    }

    // 检查是否有解析失败的
    for (result in mediaUrlsResult) {
        if (result.isLeft()) {
            return result.fold(
                ifLeft = { error -> error.left() },
                ifRight = { it.right() as Either<PostError, CreatePostCommand> }
            )
        }
    }

    // 全部成功，提取值
    val mediaUrls = mediaUrlsResult.mapNotNull { it.getOrNull() }

    return CreatePostCommand(
        authorId = authorId,
        content = this.content,
        mediaUrls = mediaUrls,
        parentId = this.parentId?.let { PostId(it) }
    ).right()
}

// ========== Domain -> Response DTO ==========

/**
 * PostDetail -> PostDetailResponse
 */
fun PostDetail.toResponse(
    isLikedByCurrentUser: Boolean? = null,
    isBookmarkedByCurrentUser: Boolean? = null
): PostDetailResponse {
    return PostDetailResponse(
        id = post.id.value,
        content = post.content.value,
        media = post.media.map { it.toDto() },
        parentId = post.parentId?.value,
        isTopLevelPost = post.parentId == null,
        createdAt = post.createdAt,
        updatedAt = post.updatedAt,
        author = author.toAuthorDto(),
        stats = stats.toDto(),
        parentPost = null, // 客户端通过 parentId 单独查询
        isLikedByCurrentUser = isLikedByCurrentUser,
        isBookmarkedByCurrentUser = isBookmarkedByCurrentUser
    )
}

/**
 * Post -> PostSummaryResponse（用于嵌套显示父 Post）
 */
fun Post.toSummaryResponse(author: User): PostSummaryResponse {
    return PostSummaryResponse(
        id = id.value,
        content = content.value,
        author = author.toAuthorDto(),
        createdAt = createdAt
    )
}

/**
 * MediaAttachment -> MediaDto
 */
fun MediaAttachment.toDto(): MediaDto {
    return MediaDto(
        url = url.value,
        type = type.name
    )
}

/**
 * User -> AuthorDto
 */
fun User.toAuthorDto(): AuthorDto {
    return AuthorDto(
        id = id.value,
        displayName = displayName,
        email = email.value,
        avatarUrl = avatarUrl
    )
}

/**
 * PostStats -> StatsDto
 */
fun PostStats.toDto(): StatsDto {
    return StatsDto(
        replyCount = replyCount,
        likeCount = likeCount,
        bookmarkCount = bookmarkCount,
        viewCount = viewCount
    )
}

// ========== Error -> HTTP ==========

/**
 * 将 Post 业务错误映射为 HTTP 状态码和错误响应
 */
fun PostError.toHttpError(): Pair<HttpStatusCode, ErrorResponse> = when (this) {
    is PostError.EmptyContent ->
        HttpStatusCode.BadRequest to ErrorResponse(
            code = "EMPTY_CONTENT",
            message = "Post 内容不能为空"
        )

    is PostError.ContentTooLong ->
        HttpStatusCode.BadRequest to ErrorResponse(
            code = "CONTENT_TOO_LONG",
            message = "Post 内容过长：最多 $max 字符，当前 $actual 字符"
        )

    is PostError.InvalidMediaUrl ->
        HttpStatusCode.BadRequest to ErrorResponse(
            code = "INVALID_MEDIA_URL",
            message = "无效的媒体 URL: $url"
        )

    is PostError.InvalidMediaType ->
        HttpStatusCode.BadRequest to ErrorResponse(
            code = "INVALID_MEDIA_TYPE",
            message = "无效的媒体类型: $received。允许的类型为: IMAGE、VIDEO"
        )

    is PostError.TooManyMedia ->
        HttpStatusCode.BadRequest to ErrorResponse(
            code = "TOO_MANY_MEDIA",
            message = "媒体数量超限：最多 4 个，当前 $count 个"
        )

    is PostError.PostNotFound ->
        HttpStatusCode.NotFound to ErrorResponse(
            code = "POST_NOT_FOUND",
            message = "Post 不存在: ${postId.value}"
        )

    is PostError.ParentPostNotFound ->
        HttpStatusCode.NotFound to ErrorResponse(
            code = "PARENT_POST_NOT_FOUND",
            message = "父 Post 不存在: ${parentId.value}"
        )

    is PostError.Unauthorized ->
        HttpStatusCode.Forbidden to ErrorResponse(
            code = "UNAUTHORIZED",
            message = "用户 $userId 无权执行操作: $action"
        )

    is PostError.MediaUploadFailed ->
        HttpStatusCode.InternalServerError to ErrorResponse(
            code = "MEDIA_UPLOAD_FAILED",
            message = "媒体上传失败: $reason"
        )

    is PostError.InteractionStateQueryFailed ->
        HttpStatusCode.InternalServerError to ErrorResponse(
            code = "INTERACTION_STATE_QUERY_FAILED",
            message = "获取交互状态失败，请稍后重试: $reason"
        )
}

/**
 * 标准化错误响应（复用 Auth 模块的）
 */
@kotlinx.serialization.Serializable
data class ErrorResponse(
    val code: String,
    val message: String
)
