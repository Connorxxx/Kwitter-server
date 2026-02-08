package com.connor.domain.failure

import com.connor.domain.model.PostId

/**
 * Post 领域错误 - 密封接口确保编译时穷尽性检查
 */
sealed interface PostError {
    // === 验证错误 ===
    data object EmptyContent : PostError
    data class ContentTooLong(val actual: Int, val max: Int) : PostError
    data class InvalidMediaUrl(val url: String) : PostError
    data class InvalidMediaType(val received: String) : PostError
    data class TooManyMedia(val count: Int) : PostError // 超过 4 个媒体

    // === 业务规则错误 ===
    data class PostNotFound(val postId: PostId) : PostError
    data class ParentPostNotFound(val parentId: PostId) : PostError
    data class Unauthorized(val userId: String, val action: String) : PostError // 无权限操作（如删除他人 Post）

    // === 基础设施错误（可选，根据需要添加）===
    data class MediaUploadFailed(val reason: String) : PostError
    data class InteractionStateQueryFailed(val reason: String) : PostError // 点赞/收藏状态查询失败
}
