package com.connor.domain.usecase

import arrow.core.Either
import com.connor.domain.failure.BookmarkError
import com.connor.domain.failure.LikeError
import com.connor.domain.failure.SearchError
import com.connor.domain.model.*
import com.connor.domain.repository.PostRepository
import com.connor.domain.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory

/**
 * 搜索 Posts 并获取当前用户的交互状态
 *
 * 职责：
 * - 搜索 Posts（不包括回复）
 * - 如果用户已认证，批量查询交互状态（避免 N+1）
 * - 返回完整的搜索结果视图
 *
 * 设计原理：
 * - 业务编排在 UseCase 层，保持 Route 层只做协议转换
 * - 交互状态查询失败时，整体返回错误（强一致性，fail-fast）
 * - 使用 Either 处理所有失败场景
 */
class SearchPostsUseCase(
    private val searchRepository: SearchRepository,
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(SearchPostsUseCase::class.java)

    /**
     * 搜索结果项目（包含交互状态）
     */
    data class SearchPostItem(
        val postDetail: PostDetail,
        val isLikedByCurrentUser: Boolean? = null,
        val isBookmarkedByCurrentUser: Boolean? = null
    )

    /**
     * 搜索错误类型的包装
     */
    sealed interface SearchPostError {
        data class SearchFailed(val error: SearchError) : SearchPostError
        data class LikesCheckFailed(val error: LikeError) : SearchPostError
        data class BookmarksCheckFailed(val error: BookmarkError) : SearchPostError
    }

    operator fun invoke(
        query: String,
        sort: PostSearchSort = PostSearchSort.RELEVANCE,
        limit: Int = 20,
        offset: Int = 0,
        currentUserId: UserId? = null
    ): Flow<Either<SearchPostError, SearchPostItem>> = flow {
        logger.info("搜索 Posts: query='$query', sort=$sort, limit=$limit, offset=$offset, userId=${currentUserId?.value}")

        // 验证查询长度
        if (query.trim().length < 2) {
            emit(Either.Left(SearchPostError.SearchFailed(SearchError.QueryTooShort(query.trim().length))))
            return@flow
        }

        // 1. 收集搜索结果并先检查错误（避免在 collect/fold lambda 中非局部 return）
        val posts = mutableListOf<PostDetail>()
        var searchError: SearchError? = null

        searchRepository.searchPosts(query, sort, limit, offset).toList().forEach { result ->
            when (result) {
                is Either.Left -> if (searchError == null) searchError = result.value
                is Either.Right -> if (searchError == null) posts.add(result.value)
            }
        }

        val firstSearchError = searchError
        if (firstSearchError != null) {
            logger.warn("搜索 Posts 失败: query='$query', error=$firstSearchError")
            emit(Either.Left(SearchPostError.SearchFailed(firstSearchError)))
            return@flow
        }

        if (posts.isEmpty()) return@flow

        // 2. 如果用户已认证，批量查询交互状态（1 次查询，不是 N 次）
        var likedPostIds: Set<PostId> = emptySet()
        var bookmarkedPostIds: Set<PostId> = emptySet()

        if (currentUserId != null) {
            val postIds = posts.map { it.post.id }

            // 批量查询点赞状态
            val likedResult = postRepository.batchCheckLiked(currentUserId, postIds)
            if (likedResult.isLeft()) {
                val error = (likedResult as Either.Left).value
                logger.warn("查询点赞状态失败: postCount=${postIds.size}, error=$error")
                emit(Either.Left(SearchPostError.LikesCheckFailed(error)))
                return@flow  // fail-fast: 立即停止，不继续查收藏
            }
            likedPostIds = (likedResult as Either.Right).value

            // 批量查询收藏状态
            val bookmarkedResult = postRepository.batchCheckBookmarked(currentUserId, postIds)
            if (bookmarkedResult.isLeft()) {
                val error = (bookmarkedResult as Either.Left).value
                logger.warn("查询收藏状态失败: postCount=${postIds.size}, error=$error")
                emit(Either.Left(SearchPostError.BookmarksCheckFailed(error)))
                return@flow  // fail-fast: 立即停止
            }
            bookmarkedPostIds = (bookmarkedResult as Either.Right).value
        }

        // 3. 返回结果（交互状态已准备好，O(1) 查找）
        posts.forEach { postDetail ->
            emit(
                Either.Right(
                    SearchPostItem(
                        postDetail = postDetail,
                        isLikedByCurrentUser = if (currentUserId != null) postDetail.post.id in likedPostIds else null,
                        isBookmarkedByCurrentUser = if (currentUserId != null) postDetail.post.id in bookmarkedPostIds else null
                    )
                )
            )
        }

        logger.info("搜索 Posts 完成: query='$query', resultCount=${posts.size}")
    }
}
