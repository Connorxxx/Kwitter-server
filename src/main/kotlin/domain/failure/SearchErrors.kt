package com.connor.domain.failure

/**
 * 搜索领域错误 - 密封接口确保编译时穷尽性检查
 */
sealed interface SearchError {
    /**
     * 查询字符串过短（最少 2 个字符）
     */
    data class QueryTooShort(val actual: Int, val min: Int = 2) : SearchError

    /**
     * 无效的排序参数
     */
    data class InvalidSortOrder(val received: String, val validOptions: List<String>) : SearchError

    /**
     * 数据库错误（FTS 不可用、索引缺失等）
     */
    data class DatabaseError(val reason: String) : SearchError

    /**
     * 批量查询交互状态失败（点赞/收藏/关注状态）
     */
    data class InteractionStateQueryFailed(val reason: String) : SearchError
}
