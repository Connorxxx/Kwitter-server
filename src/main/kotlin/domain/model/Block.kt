package com.connor.domain.model

data class Block(
    val blockerId: UserId,
    val blockedId: UserId,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(blockerId != blockedId) { "用户不能拉黑自己" }
    }
}
