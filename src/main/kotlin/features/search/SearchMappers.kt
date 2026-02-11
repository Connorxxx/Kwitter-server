package com.connor.features.search

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.core.http.ApiErrorResponse
import com.connor.domain.failure.SearchError
import com.connor.domain.model.PostSearchSort
import com.connor.domain.model.UserSearchSort
import com.connor.features.user.toDto
import io.ktor.http.*

// ========== String -> Domain Enum ==========

/**
 * 解析 PostSearchSort 字符串
 */
fun String.toPostSearchSort(): Either<SearchError, PostSearchSort> {
    return when (this.lowercase()) {
        "relevance" -> PostSearchSort.RELEVANCE.right()
        "recent" -> PostSearchSort.RECENT.right()
        else -> SearchError.InvalidSortOrder(
            received = this,
            validOptions = listOf("relevance", "recent")
        ).left()
    }
}

/**
 * 解析 UserSearchSort 字符串
 */
fun String.toUserSearchSort(): Either<SearchError, UserSearchSort> {
    return when (this.lowercase()) {
        "relevance" -> UserSearchSort.RELEVANCE.right()
        else -> SearchError.InvalidSortOrder(
            received = this,
            validOptions = listOf("relevance")
        ).left()
    }
}

// ========== Domain -> Response DTO ==========

/**
 * SearchUsersUseCase.SearchUserItem -> UserSearchResultDto
 */
fun com.connor.domain.usecase.SearchUsersUseCase.SearchUserItem.toDto(): UserSearchResultDto {
    return UserSearchResultDto(
        user = userProfile.user.toDto(),
        stats = userProfile.stats.toDto(),
        isFollowedByCurrentUser = isFollowedByCurrentUser
    )
}

// ========== Error -> HTTP ==========

/**
 * 将搜索业务错误映射为 HTTP 状态码和错误响应
 */
fun SearchError.toHttpError(): Pair<HttpStatusCode, ApiErrorResponse> = when (this) {
    is SearchError.QueryTooShort ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "QUERY_TOO_SHORT",
            message = "搜索查询至少需要 $min 个字符，当前 $actual 个字符"
        )

    is SearchError.InvalidSortOrder ->
        HttpStatusCode.BadRequest to ApiErrorResponse(
            code = "INVALID_SORT_ORDER",
            message = "无效的排序参数: $received。允许的值为: ${validOptions.joinToString()}"
        )

    is SearchError.DatabaseError ->
        HttpStatusCode.InternalServerError to ApiErrorResponse(
            code = "SEARCH_DATABASE_ERROR",
            message = "搜索失败，请稍后重试: $reason"
        )

    is SearchError.InteractionStateQueryFailed ->
        HttpStatusCode.InternalServerError to ApiErrorResponse(
            code = "INTERACTION_STATE_QUERY_FAILED",
            message = "获取交互状态失败，请稍后重试: $reason"
        )
}

/**
 * 将搜索 Post 错误映射为 HTTP 状态码和错误响应
 */
fun com.connor.domain.usecase.SearchPostsUseCase.SearchPostError.toHttpError(): Pair<HttpStatusCode, ApiErrorResponse> = when (this) {
    is com.connor.domain.usecase.SearchPostsUseCase.SearchPostError.SearchFailed ->
        error.toHttpError()

    is com.connor.domain.usecase.SearchPostsUseCase.SearchPostError.LikesCheckFailed ->
        HttpStatusCode.InternalServerError to ApiErrorResponse(
            code = "LIKES_CHECK_FAILED",
            message = "获取点赞状态失败，请稍后重试"
        )

    is com.connor.domain.usecase.SearchPostsUseCase.SearchPostError.BookmarksCheckFailed ->
        HttpStatusCode.InternalServerError to ApiErrorResponse(
            code = "BOOKMARKS_CHECK_FAILED",
            message = "获取收藏状态失败，请稍后重试"
        )
}

/**
 * 将搜索 Reply 错误映射为 HTTP 状态码和错误响应
 */
fun com.connor.domain.usecase.SearchRepliesUseCase.SearchReplyError.toHttpError(): Pair<HttpStatusCode, ApiErrorResponse> = when (this) {
    is com.connor.domain.usecase.SearchRepliesUseCase.SearchReplyError.SearchFailed ->
        error.toHttpError()

    is com.connor.domain.usecase.SearchRepliesUseCase.SearchReplyError.LikesCheckFailed ->
        HttpStatusCode.InternalServerError to ApiErrorResponse(
            code = "LIKES_CHECK_FAILED",
            message = "获取点赞状态失败，请稍后重试"
        )

    is com.connor.domain.usecase.SearchRepliesUseCase.SearchReplyError.BookmarksCheckFailed ->
        HttpStatusCode.InternalServerError to ApiErrorResponse(
            code = "BOOKMARKS_CHECK_FAILED",
            message = "获取收藏状态失败，请稍后重试"
        )
}

/**
 * 将搜索用户错误映射为 HTTP 状态码和错误响应
 */
fun com.connor.domain.usecase.SearchUsersUseCase.SearchUserError.toHttpError(): Pair<HttpStatusCode, ApiErrorResponse> = when (this) {
    is com.connor.domain.usecase.SearchUsersUseCase.SearchUserError.SearchFailed ->
        error.toHttpError()

    is com.connor.domain.usecase.SearchUsersUseCase.SearchUserError.FollowingCheckFailed ->
        HttpStatusCode.InternalServerError to ApiErrorResponse(
            code = "FOLLOWING_CHECK_FAILED",
            message = "获取关注状态失败，请稍后重试: $reason"
        )
}
