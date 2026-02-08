package com.connor.features.post

import com.connor.domain.failure.PostError
import com.connor.domain.model.*
import com.connor.domain.usecase.CreatePostCommand
import io.ktor.http.*

// ========== Request -> Domain Command ==========

/**
 * HTTP Request -> Domain Command
 */
fun CreatePostRequest.toCommand(authorId: UserId): CreatePostCommand {
    val mediaUrls = this.mediaUrls.map { media ->
        media.url to MediaType.valueOf(media.type)
    }

    return CreatePostCommand(
        authorId = authorId,
        content = this.content,
        mediaUrls = mediaUrls,
        parentId = this.parentId?.let { PostId(it) }
    )
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
}

/**
 * 标准化错误响应（复用 Auth 模块的）
 */
@kotlinx.serialization.Serializable
data class ErrorResponse(
    val code: String,
    val message: String
)
