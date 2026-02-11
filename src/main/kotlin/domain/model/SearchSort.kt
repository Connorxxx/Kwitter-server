package com.connor.domain.model

/**
 * Post 搜索排序方式
 */
enum class PostSearchSort {
    /**
     * 按相关性排序（默认） - 使用 ts_rank 计算
     */
    RELEVANCE,

    /**
     * 按时间倒序排序（最新优先）
     */
    RECENT
}

/**
 * 用户搜索排序方式
 */
enum class UserSearchSort {
    /**
     * 按相关性排序 - username (A) > displayName (B) > bio (C)
     */
    RELEVANCE
}
