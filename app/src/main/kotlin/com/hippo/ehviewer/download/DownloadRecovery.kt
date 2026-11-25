package com.hippo.ehviewer.download

/**
 * 进程被杀或系统超时后的下载恢复状态机。仅允许由 [DownloadManager] 的串行命令队列访问。
 */
internal class DownloadRecovery(
    interrupted: Collection<Long>,
    private var retry: RetryState = RetryState(),
    private val saveRetry: (RetryState) -> Unit = {},
) {
    /** 持久化的重启重试预算与等待前台标记。 */
    data class RetryState(val attempts: Int = 0, val waitingForForeground: Boolean = false)

    private val pending = interrupted.toMutableSet()

    val hasPending get() = pending.isNotEmpty()

    fun contains(gid: Long) = gid in pending

    fun add(gids: Collection<Long>) {
        pending.addAll(gids)
    }

    fun remove(gid: Long) = pending.remove(gid)

    fun takePending() = pending.toList().also { pending.clear() }

    /** 当后台无法拉起前台服务时挂起恢复，等待应用回到前台。 */
    fun deferUntilForeground(appInForeground: Boolean = false) {
        retry = retry.copy(waitingForForeground = !appInForeground)
        saveRetry(retry)
    }

    /** 应用回到前台后重置重试预算，立即允许恢复队列。 */
    fun onForeground() {
        retry = RetryState()
        saveRetry(retry)
    }

    /** 服务已成功建立前台通道时清除过期的等待前台标记，同时保留既有的崩溃循环重试计数。 */
    fun clearWaitingForForeground() {
        if (!retry.waitingForForeground) return
        retry = retry.copy(waitingForForeground = false)
        saveRetry(retry)
    }

    /** 计算下一次指数退避延迟（最多 5 次）；在真正启动服务前先行持久化计数以防止崩溃死循环。 */
    fun nextDelayMillis(immediate: Boolean = false): Long? {
        if (!hasPending || retry.waitingForForeground || retry.attempts >= 5) return null
        val delay = if (immediate) 0L else 1_000L shl retry.attempts.coerceAtLeast(0)
        retry = retry.copy(attempts = retry.attempts.coerceAtLeast(0) + 1)
        saveRetry(retry)
        return delay
    }
}
