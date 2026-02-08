package com.connor.domain.failure

import com.connor.domain.model.PostId
import com.connor.domain.model.UserId

/**
 * Bookmark领域错误
 */
sealed interface BookmarkError {
    data class PostNotFound(val postId: PostId) : BookmarkError
    data class AlreadyBookmarked(val userId: UserId, val postId: PostId) : BookmarkError
    data class NotBookmarked(val userId: UserId, val postId: PostId) : BookmarkError
    data class DatabaseError(val reason: String) : BookmarkError
}
