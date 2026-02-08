package com.connor.data.db.mapping

import com.connor.data.db.schema.BookmarksTable
import com.connor.domain.model.Bookmark
import com.connor.domain.model.BookmarkId
import com.connor.domain.model.PostId
import com.connor.domain.model.UserId
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toBookmark(): Bookmark {
    return Bookmark(
        id = BookmarkId(this[BookmarksTable.id]),
        userId = UserId(this[BookmarksTable.userId]),
        postId = PostId(this[BookmarksTable.postId]),
        createdAt = this[BookmarksTable.createdAt]
    )
}
