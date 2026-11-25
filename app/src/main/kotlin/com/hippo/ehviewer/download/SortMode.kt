package com.hippo.ehviewer.download

import com.ehviewer.core.database.model.DownloadInfo
import com.hippo.ehviewer.client.EhUtils

data class SortMode(val field: Field, val order: Order, val groupByDownloadLabel: Boolean = false) {
    val flag
        get() = groupByDownloadLabel.compareTo(false) shl 4 or (field.value shl 1) or order.value

    enum class Field(val value: Int) {
        GID(0),
        TITLE(1),
        TIME(2),
        PAGES(4),
        CUSTOM(5),
    }

    enum class Order(val value: Int) {
        ASC(0),
        DESC(1),
    }

    fun comparator(manualOrderRanks: Map<Long, Int> = emptyMap()): Comparator<DownloadInfo> {
        var comparator = when (field) {
            Field.GID -> compareBy { it.gid }
            Field.TITLE -> compareBy { EhUtils.getSuitableTitle(it) }
            Field.TIME -> compareBy { it.time }
            Field.PAGES -> compareBy<DownloadInfo> { it.pages }
            Field.CUSTOM -> Comparator<DownloadInfo> { a, b ->
                val ra = manualOrderRanks[a.gid]
                val rb = manualOrderRanks[b.gid]
                when {
                    ra != null && rb != null -> ra.compareTo(rb)
                    ra == null && rb == null -> b.time.compareTo(a.time)
                    // 尚未排过序的新下载画廊排在列表最前面
                    ra == null -> -1
                    else -> 1
                }
            }
        }
        if (order == Order.DESC && field != Field.CUSTOM) {
            comparator = comparator.reversed()
        }
        if (field == Field.PAGES) {
            comparator = comparator.thenByDescending { it.time }
        }
        if (groupByDownloadLabel) {
            comparator = compareBy<DownloadInfo> { it.label }.then(comparator)
        }
        return comparator
    }

    companion object {
        fun from(flag: Int): SortMode {
            val fieldValue = flag and 0xF shr 1
            val orderValue = flag and 0x1
            val groupByDownloadLabel = flag shr 4 == 1
            val field = Field.entries.find { it.value == fieldValue } ?: Default.field
            val order = Order.entries.find { it.value == orderValue } ?: Default.order
            return SortMode(field, order, groupByDownloadLabel)
        }
        val Default = SortMode(Field.TIME, Order.DESC)
        val All = [
            Default,
            SortMode(Field.TIME, Order.ASC),
            SortMode(Field.GID, Order.DESC),
            SortMode(Field.GID, Order.ASC),
            SortMode(Field.TITLE, Order.ASC),
            SortMode(Field.TITLE, Order.DESC),
            SortMode(Field.PAGES, Order.ASC),
            SortMode(Field.PAGES, Order.DESC),
            SortMode(Field.CUSTOM, Order.ASC),
        ]
    }
}

/**
 * 将用户在筛选子集（例如某标签下）拖拽重排后的可见画廊顺序，按子集原先在全局列表中所占用的槽位填回，
 * 避免影响不在当前筛选视图中的其他画廊相对顺序。
 */
fun mergeManualOrder(
    currentGlobalOrder: List<Long>,
    visibleSubsetNewOrder: List<Long>,
): List<Long> {
    if (visibleSubsetNewOrder.isEmpty()) return currentGlobalOrder
    val visibleSet = visibleSubsetNewOrder.toHashSet()
    val result = currentGlobalOrder.toMutableList()
    var subsetCursor = 0
    for (i in result.indices) {
        if (result[i] in visibleSet && subsetCursor < visibleSubsetNewOrder.size) {
            result[i] = visibleSubsetNewOrder[subsetCursor++]
        }
    }
    if (subsetCursor < visibleSubsetNewOrder.size) {
        val existingSet = result.toHashSet()
        for (i in subsetCursor until visibleSubsetNewOrder.size) {
            val gid = visibleSubsetNewOrder[i]
            if (existingSet.add(gid)) {
                result.add(gid)
            }
        }
    }
    return result
}
