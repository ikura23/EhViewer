package com.hippo.ehviewer.ui.reader

import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun PageStatus.isTerminal(): Boolean =
    this is PageStatus.Ready || this is PageStatus.Error || this is PageStatus.Blocked

/**
 * 事件驱动等待单页到达终态（Ready / Error / Blocked）；若仍未结束且提供了定时重试回调，则按固定间隔触发补拉。
 */
internal suspend fun awaitPageTerminal(
    page: Page,
    timeoutMillis: Long,
    onPeriodicRetry: (suspend () -> Unit)? = null,
    retryIntervalMillis: Long = 1_000L,
): PageStatus? {
    val initial = page.statusFlow.value
    if (initial.isTerminal()) return initial
    return withTimeoutOrNull(timeoutMillis) {
        coroutineScope {
            val retryJob = if (onPeriodicRetry != null) {
                launch {
                    while (true) {
                        delay(retryIntervalMillis)
                        if (page.statusFlow.value.isTerminal()) break
                        onPeriodicRetry()
                    }
                }
            } else {
                null
            }
            try {
                page.statusFlow.first { it.isTerminal() }
            } finally {
                retryJob?.cancel()
            }
        }
    }
}

/**
 * 监听目标页面直到就绪、报错或用户切离当前页。
 */
internal suspend fun awaitPageReadyWhileOnPage(
    page: Page,
    currentPageFlow: Flow<Int>,
    pageIndex: Int,
    timeoutMillis: Long,
): Boolean {
    val result = withTimeoutOrNull(timeoutMillis) {
        combine(page.statusFlow, currentPageFlow) { status, current ->
            status to current
        }.first { (status, current) ->
            current != pageIndex || status.isTerminal()
        }
    } ?: return false
    val (status, current) = result
    return current == pageIndex && status is PageStatus.Ready
}

/**
 * 基于样本页的 `statusFlow` 事件驱动抽样判断是否应自动启用动图播放模式。
 */
internal suspend fun sampleAnimatedPages(
    pages: List<Page>,
    sampleRange: IntRange,
    timeoutMillis: Long,
): Boolean {
    if (sampleRange.isEmpty()) return false
    val sampleTotal = sampleRange.count()
    val observedAnimated = HashMap<Int, Boolean>(sampleTotal)

    fun evaluate(): Boolean? {
        for (i in sampleRange) {
            if (i in observedAnimated) continue
            when (val status = pages[i].statusFlow.value) {
                is PageStatus.Ready -> observedAnimated[i] = status.image.isAnimated
                is PageStatus.Error, is PageStatus.Blocked -> observedAnimated[i] = false
                else -> Unit
            }
        }
        val animCount = observedAnimated.values.count { it }
        val nonAnimCount = observedAnimated.size - animCount
        // 非动图已超过半数，立刻结束抽样，不干扰普通画廊阅读。
        if (nonAnimCount * 2 > sampleTotal) return false
        // 已确认动图过半且抽样过半，激活自动动图播放。
        if (observedAnimated.size >= minOf(sampleTotal, 4) && animCount * 2 >= observedAnimated.size) {
            return true
        }
        if (observedAnimated.size >= sampleTotal) return false
        return null
    }

    evaluate()?.let { return it }

    return withTimeoutOrNull(timeoutMillis) {
        sampleRange
            .map { idx -> pages[idx].statusFlow }
            .merge()
            .map { evaluate() }
            .firstOrNull { it != null }
    } ?: false
}
