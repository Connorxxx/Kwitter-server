package com.connor.core.utlis

import java.util.concurrent.atomic.AtomicLong

/**
 * Snowflake ID 生成器（Twitter Snowflake 算法变体）
 *
 * 64-bit 结构：
 * - 1 bit:  符号位（始终为 0）
 * - 41 bits: 毫秒级时间戳（自定义 epoch 起算，可用约 69 年）
 * - 10 bits: 机器/工作节点 ID（0~1023）
 * - 12 bits: 序列号（同一毫秒内递增，0~4095）
 *
 * 生成的 ID 是单调递增的 Long，转为 String 后约 18~19 位数字。
 */
object SnowflakeIdGenerator {

    // 自定义 epoch: 2025-01-01T00:00:00Z
    private const val EPOCH = 1735689600000L

    private const val WORKER_ID_BITS = 10
    private const val SEQUENCE_BITS = 12

    private const val MAX_WORKER_ID = (1L shl WORKER_ID_BITS) - 1  // 1023
    private const val MAX_SEQUENCE = (1L shl SEQUENCE_BITS) - 1    // 4095

    private const val WORKER_ID_SHIFT = SEQUENCE_BITS              // 12
    private const val TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS  // 22

    private val workerId: Long = (ProcessHandle.current().pid() % (MAX_WORKER_ID + 1))

    // 将 lastTimestamp 和 sequence 打包到一个 AtomicLong 中
    // 高 52 bits = timestamp, 低 12 bits = sequence
    private val state = AtomicLong(0L)

    fun nextId(): Long {
        while (true) {
            val now = System.currentTimeMillis()
            val elapsed = now - EPOCH
            require(elapsed >= 0) { "System clock is before snowflake epoch" }

            val currentState = state.get()
            val lastTimestamp = currentState ushr SEQUENCE_BITS
            val lastSequence = currentState and MAX_SEQUENCE

            if (elapsed == lastTimestamp) {
                val seq = lastSequence + 1
                if (seq > MAX_SEQUENCE) {
                    // 当前毫秒序列号耗尽，自旋等待下一毫秒
                    Thread.onSpinWait()
                    continue
                }
                val newState = (elapsed shl SEQUENCE_BITS) or seq
                if (state.compareAndSet(currentState, newState)) {
                    return formatId(elapsed, seq)
                }
            } else if (elapsed > lastTimestamp) {
                val newState = elapsed shl SEQUENCE_BITS  // sequence = 0
                if (state.compareAndSet(currentState, newState)) {
                    return formatId(elapsed, 0)
                }
            }
            // CAS 失败或时钟回拨 → 重试
        }
    }

    private fun formatId(timestamp: Long, sequence: Long): Long {
        return (timestamp shl TIMESTAMP_SHIFT) or
            (workerId shl WORKER_ID_SHIFT) or
            sequence
    }
}
