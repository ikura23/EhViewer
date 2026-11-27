package com.hippo.ehviewer.download

import android.content.Context
import com.ehviewer.core.database.model.DownloadInfo
import com.hippo.ehviewer.client.EhUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import splitties.init.appCtx

private val LEADING_GID_PREFIX_REGEX = Regex("""^\s*\d{5,}\s*[-_]\s*""")
private val MULTI_WHITESPACE_REGEX = Regex("""\s+""")

/**
 * 同名新旧版本画廊分组信息：
 * [newest] 为系统综合判定推荐保留的最新条目，[older] 为同名的较旧版本或重复条目。
 */
data class DuplicateGalleryGroup(
    val key: String,
    val displayTitle: String,
    val newest: DownloadInfo,
    val older: List<DownloadInfo>,
)

/**
 * 将画廊标题归一化用于同名重复判定：
 * 仅剥离前导 GID 编号（5 位及以上数字）、折叠空白与转小写，保留尾部卷数/集数后缀（如 `2`、`collection 4`），
 * 避免把同系列不同集数错判为同一画廊。
 */
internal fun normalizeGalleryTitleForDuplicateCheck(title: String?): String {
    if (title.isNullOrBlank()) return ""
    val withoutPrefix = LEADING_GID_PREFIX_REGEX.replaceFirst(title, "")
    return withoutPrefix.trim().replace(MULTI_WHITESPACE_REGEX, " ").lowercase()
}

/**
 * 为一组同名新旧版画廊生成稳定的忽略键。
 */
internal fun duplicateGroupIgnoreKey(newestGid: Long, olderGids: Collection<Long>): String =
    "$newestGid:${olderGids.sorted().joinToString(",")}"

/**
 * 判断旧版本与新版本是否具备在线增量合并（复用旧页、补齐后删旧版）的条件。
 */
fun canMergeOlderVersionToNewer(oldInfo: DownloadInfo, newInfo: DownloadInfo): Boolean =
    oldInfo.gid > 0L &&
        newInfo.gid > 0L &&
        oldInfo.gid != newInfo.gid &&
        isValidGalleryHexToken(newInfo.token)

private val duplicatePriorityComparator = Comparator<DownloadInfo> { a, b ->
    // 正整数 GID 优先于本地负数 GID
    val aRemote = a.gid > 0L
    val bRemote = b.gid > 0L
    if (aRemote != bRemote) return@Comparator if (aRemote) -1 else 1

    // 应用主下载目录优先于外部“本地”目录
    if (a.isLocal != b.isLocal) return@Comparator if (!a.isLocal) -1 else 1

    // 同一空间下 GID 越大版本越新
    val gidCmp = b.gid.compareTo(a.gid)
    if (gidCmp != 0) return@Comparator gidCmp

    // 页数更多者优先
    val pagesCmp = b.pages.compareTo(a.pages)
    if (pagesCmp != 0) return@Comparator pagesCmp

    b.time.compareTo(a.time)
}

/**
 * 在已下载画廊列表中识别名称一致但 GID 不同的新旧版本分组。
 */
internal fun findDuplicateGalleryGroups(
    items: List<DownloadInfo>,
    ignoredKeys: Set<String> = emptySet(),
    titleSelector: (DownloadInfo) -> String = EhUtils::getSuitableTitle,
): List<DuplicateGalleryGroup> {
    if (items.size < 2) return emptyList()
    val grouped = linkedMapOf<String, MutableList<DownloadInfo>>()
    for (info in items) {
        val rawTitle = titleSelector(info)
        val normalized = normalizeGalleryTitleForDuplicateCheck(rawTitle)
        if (normalized.isEmpty()) continue
        grouped.getOrPut(normalized) { mutableListOf() }.add(info)
    }

    val results = mutableListOf<DuplicateGalleryGroup>()
    for (bucket in grouped.values) {
        if (bucket.size < 2) continue
        val sorted = bucket.sortedWith(duplicatePriorityComparator)
        val newest = sorted.first()
        val older = sorted.drop(1).sortedBy { it.gid }
        val key = duplicateGroupIgnoreKey(newest.gid, older.map { it.gid })
        if (key in ignoredKeys) continue
        val displayTitle = titleSelector(newest).trim().ifEmpty { newest.title.orEmpty() }
        results += DuplicateGalleryGroup(
            key = key,
            displayTitle = displayTitle,
            newest = newest,
            older = older,
        )
    }
    return results.sortedByDescending { it.newest.time }
}

/**
 * 持久化记录用户选择“暂不处理/忽略”的同名重复画廊分组键。
 */
object DuplicateGalleryIgnoreStore {
    private const val PREF_NAME = "duplicate_gallery_ignore"
    private const val KEY_IGNORED = "ignored_group_keys"

    private val prefs by lazy {
        appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    private val _ignoredKeys by lazy {
        MutableStateFlow(prefs.getStringSet(KEY_IGNORED, emptySet())?.toSet() ?: emptySet())
    }
    val ignoredKeys: StateFlow<Set<String>> by lazy { _ignoredKeys.asStateFlow() }

    fun ignore(key: String) {
        val updated = _ignoredKeys.value + key
        _ignoredKeys.value = updated
        prefs.edit().putStringSet(KEY_IGNORED, updated).apply()
    }
}
