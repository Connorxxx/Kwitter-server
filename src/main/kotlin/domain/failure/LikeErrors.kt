package com.connor.domain.failure

import com.connor.domain.model.PostId
import com.connor.domain.model.UserId

/**
 * Like领域错误
 */
sealed interface LikeError {
    data class PostNotFound(val postId: PostId) : LikeError
    data class AlreadyLiked(val userId: UserId, val postId: PostId) : LikeError
    data class NotLiked(val userId: UserId, val postId: PostId) : LikeError
    data class DatabaseError(val reason: String) : LikeError
}
