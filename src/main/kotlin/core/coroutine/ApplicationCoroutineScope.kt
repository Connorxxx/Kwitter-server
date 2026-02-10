package com.connor.core.coroutine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory

/**
 * 应用级协程作用域
 *
 * 用途：
 * - 后台任务（如实时通知推送）
 * - 不绑定到具体请求的异步操作
 *
 * 生命周期：
 * - 应用启动时创建
 * - 应用停止时取消（优雅关闭）
 *
 * 特性：
 * - SupervisorJob：子协程失败不影响其他协程
 * - Dispatchers.Default：适合 CPU 密集型任务
 */
class ApplicationCoroutineScope : CoroutineScope {
    private val logger = LoggerFactory.getLogger(ApplicationCoroutineScope::class.java)

    private val job = SupervisorJob()

    override val coroutineContext = job + Dispatchers.Default

    /**
     * 取消所有协程（应用停止时调用）
     */
    fun shutdown() {
        logger.info("Shutting down application coroutine scope")
        job.cancel()
        logger.info("Application coroutine scope shut down complete")
    }
}
