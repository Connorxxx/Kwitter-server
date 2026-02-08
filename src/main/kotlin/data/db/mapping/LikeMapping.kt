package com.connor.data.db.mapping

import com.connor.data.db.schema.LikesTable
import com.connor.domain.model.Like
import com.connor.domain.model.LikeId
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toLike(): Like {
    return Like(
        id = LikeId(this[LikesTable.id]),
        userId = UserId(this[LikesTable.userId]),
        postId = PostId(this[LikesTable.postId]),
        createdAt = this[LikesTable.createdAt]
    )
}
