package com.hippo.ehviewer.ui.experimental

import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.data.ListUrlBuilder.Companion.MODE_NORMAL
import com.hippo.ehviewer.ui.main.AdvanceTable

/**
 * 实验性新版界面底部 5 个一级导航页签。
 */
enum class ExperimentalBottomTab {
    HOME,
    HOT,
    SEARCH,
    DOWNLOADS,
    ME,
}

/**
 * 支持展示实验性底部导航栏的顶层路由名称集合。
 */
internal val DEFAULT_EXP_TOP_LEVEL_ROUTES = setOf(
    "home_page_screen",
    "experimental_hot_screen",
    "whatshot_screen",
    "toplist_screen",
    "subscription_screen",
    "experimental_search_screen",
    "downloads_screen",
    "experimental_me_screen",
)

/**
 * 判断当前是否应展示实验性底部导航栏：仅在实验开关开启且位于一级 Tab 页面时显示。
 */
internal fun shouldShowExperimentalBottomBar(
    enabled: Boolean,
    currentRoute: String?,
    topLevelRoutes: Set<String> = DEFAULT_EXP_TOP_LEVEL_ROUTES,
): Boolean {
    if (!enabled || currentRoute.isNullOrBlank()) return false
    val baseRoute = currentRoute.substringBefore('/')
    return baseRoute in topLevelRoutes || currentRoute in topLevelRoutes
}

/**
 * 搜索页高级筛选状态（完整映射 EhViewer 原生分类位掩码、语言、星级、页数范围与 AdvanceTable）。
 */
data class ExperimentalSearchFilterState(
    val categoryMask: Int = EhUtils.NONE,
    val languageIndex: Int = -1,
    val minRating: Int = 0,
    val fromPage: Int = 0,
    val toPage: Int = 0,
    val onlyAnimated: Boolean = false,
    val showExpunged: Boolean = false,
    val onlyTorrents: Boolean = false,
    val disableLangFilter: Boolean = false,
    val disableUploaderFilter: Boolean = false,
    val disableTagFilter: Boolean = false,
) {
    fun isCategoryChecked(bit: Int): Boolean =
        categoryMask != EhUtils.NONE && (categoryMask and bit) != 0

    fun toggleCategory(bit: Int): ExperimentalSearchFilterState {
        val base = if (categoryMask == EhUtils.NONE) 0 else categoryMask
        val next = base xor bit
        return copy(categoryMask = if (next == 0) EhUtils.NONE else next)
    }

    fun activeCount(): Int {
        var count = 0
        if (categoryMask != EhUtils.NONE && categoryMask != EhUtils.ALL_CATEGORY) count++
        if (languageIndex >= 0) count++
        if (minRating > 0) count++
        if (fromPage > 0 || toPage > 0) count++
        if (onlyAnimated) count++
        if (showExpunged) count++
        if (onlyTorrents) count++
        if (disableLangFilter) count++
        if (disableUploaderFilter) count++
        if (disableTagFilter) count++
        return count
    }

    fun toAdvanceSearchMask(): Int {
        var mask = 0
        if (showExpunged) mask = mask or AdvanceTable.SH
        if (onlyTorrents) mask = mask or AdvanceTable.STO
        if (disableLangFilter) mask = mask or AdvanceTable.SFL
        if (disableUploaderFilter) mask = mask or AdvanceTable.SFU
        if (disableTagFilter) mask = mask or AdvanceTable.SFT
        return mask
    }

    fun buildListUrlBuilder(rawKeyword: String): ListUrlBuilder {
        val trimmed = rawKeyword.trim()
        val finalKeyword = if (onlyAnimated && !trimmed.contains("animated", ignoreCase = true)) {
            if (trimmed.isEmpty()) "other:animated$" else "$trimmed other:animated$"
        } else {
            trimmed
        }
        val advMask = toAdvanceSearchMask()
        return ListUrlBuilder(
            mode = MODE_NORMAL,
            category = categoryMask,
            keyword = finalKeyword.ifEmpty { null },
            language = languageIndex,
            advanceSearch = if (advMask == 0) -1 else advMask,
            minRating = if (minRating in 2..5) minRating else -1,
            pageFrom = if (fromPage > 0) fromPage else -1,
            pageTo = if (toPage > 0) toPage else -1,
        )
    }
}

/**
 * 常用语言快速下标查找（按 GalleryInfo.S_LANG_TAGS 顺序动态匹配）。
 */
internal fun findLanguageFilterIndex(tagSuffix: String): Int =
    GalleryInfo.S_LANG_TAGS.indexOfFirst { it.equals("language:$tagSuffix", ignoreCase = true) }
