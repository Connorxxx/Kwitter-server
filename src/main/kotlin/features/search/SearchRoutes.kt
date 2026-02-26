package com.connor.features.search

import arrow.core.Either
import com.connor.core.http.ApiErrorResponse
import com.connor.domain.model.PostSearchSort
import com.connor.domain.model.UserId
import com.connor.domain.model.UserSearchSort
import com.connor.domain.usecase.SearchPostsUseCase
import com.connor.domain.usecase.SearchRepliesUseCase
import com.connor.domain.usecase.SearchUsersUseCase
import com.connor.features.post.PostListResponse
import com.connor.features.post.toResponse
import com.connor.plugins.tryResolvePrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SearchRoutes")

fun Route.searchRoutes(
    searchPostsUseCase: SearchPostsUseCase,
    searchRepliesUseCase: SearchRepliesUseCase,
    searchUsersUseCase: SearchUsersUseCase
) {
    route("/v1/search") {
        // ========== 公开路由（软鉴权）==========
        // 未认证用户可以搜索，认证用户获得交互状态（点赞/收藏/关注）

            /**
             * GET /v1/search/posts?q=kotlin&sort=relevance&limit=20&offset=0
             * 搜索 Posts（不包括回复）
             * 支持排序：relevance（默认）、recent
             */
            get("/posts") {
                val startTime = System.currentTimeMillis()

                // 解析查询参数
                val query = call.request.queryParameters["q"] ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorResponse("MISSING_QUERY", "缺少搜索查询参数 'q'")
                    )
                    return@get
                }

                val sortParam = call.request.queryParameters["sort"] ?: "relevance"
                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val limit = rawLimit.coerceIn(1, 100)  // 下限 1，上限 100
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

                try {
                    logger.info("搜索 Posts: query='$query', sort=$sortParam, limit=$limit, offset=$offset")

                    // 解析排序参数
                    val sort = sortParam.toPostSearchSort().fold(
                        ifLeft = { error ->
                            val (status, body) = error.toHttpError()
                            call.respond(status, body)
                            return@get
                        },
                        ifRight = { it }
                    )

                    // 获取当前用户 ID（如果已认证）
                    val currentUserId = call.tryResolvePrincipal()?.userId?.let(::UserId)

                    // 调用 Use Case（拉取 limit + 1 条用于判断 hasMore）
                    val searchResults = searchPostsUseCase(query, sort, limit + 1, offset, currentUserId).toList()

                    // fail-fast 错误检查
                    val failures = searchResults.filterIsInstance<Either.Left<*>>()
                    if (failures.isNotEmpty()) {
                        val error = (failures.first() as Either.Left<SearchPostsUseCase.SearchPostError>).value
                        val (status, body) = error.toHttpError()
                        val duration = System.currentTimeMillis() - startTime
                        logger.warn("搜索 Posts 失败: query='$query', error=${error.javaClass.simpleName}, duration=${duration}ms")
                        call.respond(status, body)
                        return@get
                    }

                    // 提取成功项
                    val successItems = searchResults.filterIsInstance<Either.Right<*>>()
                        .map { (it as Either.Right<SearchPostsUseCase.SearchPostItem>).value }

                    // limit + 1 分页判断
                    val hasMore = successItems.size > limit
                    val itemsToReturn = if (hasMore) successItems.take(limit) else successItems

                    // 映射为响应 DTO
                    val postsResponse = itemsToReturn.map { item ->
                        item.postDetail.toResponse(
                            isLikedByCurrentUser = item.isLikedByCurrentUser,
                            isBookmarkedByCurrentUser = item.isBookmarkedByCurrentUser
                        )
                    }

                    val duration = System.currentTimeMillis() - startTime
                    logger.info("搜索 Posts 成功: query='$query', resultCount=${itemsToReturn.size}, hasMore=$hasMore, duration=${duration}ms")

                    call.respond(
                        HttpStatusCode.OK,
                        SearchPostsResponse(
                            posts = postsResponse,
                            hasMore = hasMore,
                            query = query,
                            sort = sortParam
                        )
                    )

                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - startTime
                    logger.error("搜索 Posts 异常: query='$query', duration=${duration}ms, error=${e.message}", e)
                    throw e
                }
            }

            /**
             * GET /v1/search/replies?q=thanks&limit=20&offset=0
             * 搜索 Replies（只返回回复）
             * 按相关性排序
             */
            get("/replies") {
                val startTime = System.currentTimeMillis()

                // 解析查询参数
                val query = call.request.queryParameters["q"] ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorResponse("MISSING_QUERY", "缺少搜索查询参数 'q'")
                    )
                    return@get
                }

                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val limit = rawLimit.coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

                try {
                    logger.info("搜索 Replies: query='$query', limit=$limit, offset=$offset")

                    // 获取当前用户 ID（如果已认证）
                    val currentUserId = call.tryResolvePrincipal()?.userId?.let(::UserId)

                    // 调用 Use Case（拉取 limit + 1 条用于判断 hasMore）
                    val searchResults = searchRepliesUseCase(query, limit + 1, offset, currentUserId).toList()

                    // fail-fast 错误检查
                    val failures = searchResults.filterIsInstance<Either.Left<*>>()
                    if (failures.isNotEmpty()) {
                        val error = (failures.first() as Either.Left<SearchRepliesUseCase.SearchReplyError>).value
                        val (status, body) = error.toHttpError()
                        val duration = System.currentTimeMillis() - startTime
                        logger.warn("搜索 Replies 失败: query='$query', error=${error.javaClass.simpleName}, duration=${duration}ms")
                        call.respond(status, body)
                        return@get
                    }

                    // 提取成功项
                    val successItems = searchResults.filterIsInstance<Either.Right<*>>()
                        .map { (it as Either.Right<SearchRepliesUseCase.SearchReplyItem>).value }

                    // limit + 1 分页判断
                    val hasMore = successItems.size > limit
                    val itemsToReturn = if (hasMore) successItems.take(limit) else successItems

                    // 映射为响应 DTO
                    val repliesResponse = itemsToReturn.map { item ->
                        item.postDetail.toResponse(
                            isLikedByCurrentUser = item.isLikedByCurrentUser,
                            isBookmarkedByCurrentUser = item.isBookmarkedByCurrentUser
                        )
                    }

                    val duration = System.currentTimeMillis() - startTime
                    logger.info("搜索 Replies 成功: query='$query', resultCount=${itemsToReturn.size}, hasMore=$hasMore, duration=${duration}ms")

                    call.respond(
                        HttpStatusCode.OK,
                        SearchRepliesResponse(
                            replies = repliesResponse,
                            hasMore = hasMore,
                            query = query
                        )
                    )

                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - startTime
                    logger.error("搜索 Replies 异常: query='$query', duration=${duration}ms, error=${e.message}", e)
                    throw e
                }
            }

            /**
             * GET /v1/search/users?q=john&limit=20&offset=0
             * 搜索用户
             * 按相关性排序（username > displayName > bio）
             */
            get("/users") {
                val startTime = System.currentTimeMillis()

                // 解析查询参数
                val query = call.request.queryParameters["q"] ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorResponse("MISSING_QUERY", "缺少搜索查询参数 'q'")
                    )
                    return@get
                }

                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val limit = rawLimit.coerceIn(1, 100)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

                try {
                    logger.info("搜索用户: query='$query', limit=$limit, offset=$offset")

                    // 获取当前用户 ID（如果已认证）
                    val currentUserId = call.tryResolvePrincipal()?.userId?.let(::UserId)

                    // 调用 Use Case（拉取 limit + 1 条用于判断 hasMore）
                    val searchResults = searchUsersUseCase(query, UserSearchSort.RELEVANCE, limit + 1, offset, currentUserId).toList()

                    // fail-fast 错误检查
                    val failures = searchResults.filterIsInstance<Either.Left<*>>()
                    if (failures.isNotEmpty()) {
                        val error = (failures.first() as Either.Left<SearchUsersUseCase.SearchUserError>).value
                        val (status, body) = error.toHttpError()
                        val duration = System.currentTimeMillis() - startTime
                        logger.warn("搜索用户失败: query='$query', error=${error.javaClass.simpleName}, duration=${duration}ms")
                        call.respond(status, body)
                        return@get
                    }

                    // 提取成功项
                    val successItems = searchResults.filterIsInstance<Either.Right<*>>()
                        .map { (it as Either.Right<SearchUsersUseCase.SearchUserItem>).value }

                    // limit + 1 分页判断
                    val hasMore = successItems.size > limit
                    val itemsToReturn = if (hasMore) successItems.take(limit) else successItems

                    // 映射为响应 DTO
                    val usersResponse = itemsToReturn.map { it.toDto() }

                    val duration = System.currentTimeMillis() - startTime
                    logger.info("搜索用户成功: query='$query', resultCount=${itemsToReturn.size}, hasMore=$hasMore, duration=${duration}ms")

                    call.respond(
                        HttpStatusCode.OK,
                        SearchUsersResponse(
                            users = usersResponse,
                            hasMore = hasMore,
                            query = query
                        )
                    )

                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - startTime
                    logger.error("搜索用户异常: query='$query', duration=${duration}ms, error=${e.message}", e)
                    throw e
                }
            }
    }
}

