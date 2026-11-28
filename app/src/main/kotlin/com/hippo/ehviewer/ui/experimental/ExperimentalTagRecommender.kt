package com.hippo.ehviewer.ui.experimental

import android.content.Context
import coil3.imageLoader
import coil3.request.ImageRequest
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.data.ListUrlBuilder.Companion.MODE_NORMAL
import com.hippo.ehviewer.client.data.ListUrlBuilder.Companion.MODE_TOPLIST
import com.hippo.ehviewer.client.data.ListUrlBuilder.Companion.MODE_WHATS_HOT
import com.hippo.ehviewer.coil.ehUrl
import java.io.File
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.ln
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 搜索页推荐的子页签模式。
 */
enum class RecommendTabMode {
    TAGS,
    PARODIES,
    ARTISTS,
}

/**
 * 基于真实高星画廊聚合出来的推荐标签条目。
 */
@Serializable
data class RecommendedTagItem(
    val tagKey: String,
    val namespace: String,
    val rawTag: String,
    val query: String,
    val displayTitle: String,
    val subtitle: String,
    val score: Double,
    val sampleCount: Int,
    val coverGallery: BaseGalleryInfo,
    val gradientStartArgb: Long,
    val gradientEndArgb: Long,
)

@Serializable
internal data class CachedRecommendations(
    val timestampMillis: Long,
    val items: List<RecommendedTagItem>,
)

/**
 * 单次从画廊列表中抽取推荐标签的结果，携带本批新增的 tagKey 与已占用封面 gid/thumbKey 用于跨批去重。
 */
data class RecommendedBatchResult(
    val items: List<RecommendedTagItem>,
    val addedTagKeys: Set<String>,
    val addedCoverGids: Set<Long>,
    val addedCoverThumbKeys: Set<String> = emptySet(),
)

/**
 * 高星画廊批次拉取结果，封装分页游标状态以支持下拉刷新换源与下滑无限续翻。
 */
data class HighRatedFetchResult(
    val galleries: List<BaseGalleryInfo>,
    val nextCursor: String?,
    val nextToplistPage: Int,
    val usedToplist: Boolean,
    val canFallbackNormal: Boolean,
    val hasMore: Boolean,
)

private val NOISE_OTHER_TAGS = setOf(
    "multipanel",
    "incomplete",
    "sample",
    "extraneous ads",
    "ai generated",
    "replaced",
    "watermarked",
    "pixelated",
    "mosaic censorship",
    "already uploaded",
    "rough translation",
    "machine translated",
    "rewrite",
    "variant set",
    "non-h imageset",
    "out of order",
    "missing cover",
    "poor grammar",
    "rough grammar",
    "text cleaned",
    "speechless",
)

private val NON_H_BLOCKED_TAGS = setOf(
    "uncensored",
    "mosaic censorship",
    "decensored",
)

private val MALE_RELATED_EXACT = setOf(
    "males only",
    "yaoi",
    "bara",
    "shotacon",
    "tomgirl",
    "crossdressing",
    "otokonoko",
    "josou seme",
    "sole male",
    "male on dickgirl",
    "dickgirl on male",
    "furry male",
    "dilf",
    "old man",
    "bbm",
    "boys love",
)

private val MALE_WORD_REGEX = Regex(
    """\b(male|males|yaoi|shotacon|tomgirl|crossdressing|bara|otokonoko|shota|dilf)\b""",
    RegexOption.IGNORE_CASE,
)

private fun isMaleRelatedTag(namespace: String, rawTag: String): Boolean {
    if (namespace == "male") return true
    if (rawTag in MALE_RELATED_EXACT) return true
    return MALE_WORD_REGEX.containsMatchIn(rawTag)
}

private val GRADIENT_PALETTE = listOf(
    0xFF283593L to 0xFF0D1433L,
    0xFF1565C0L to 0xFF0B2C52L,
    0xFF7B1FA2L to 0xFF22093AL,
    0xFF006978L to 0xFF06232BL,
    0xFFC2185BL to 0xFF3E0A21L,
    0xFF512DA8L to 0xFF140836L,
    0xFF2E7D32L to 0xFF0C2610L,
    0xFF8D4E2AL to 0xFF2E180BL,
)

private val SINGLE_TAG_QUERY_REGEX = Regex("""^([a-z]+):(?:"([^"$]+)\$?"|([^\s"$]+)\$?)$""", RegexOption.IGNORE_CASE)

/**
 * 将单个精确标签查询式（如 `other:"multi-work series$"` 或 `parody:"blue archive$"`）译为易读的标题（如 `其他：系列作品`）；
 * 若包含多个查询词或未命中翻译则返回 null，回退原始搜索词。
 */
internal fun formatSingleTagTitle(
    keyword: String,
    translateNs: (String) -> String?,
    translateTag: (nsPrefix: String?, rawTag: String) -> String?,
): String? {
    val match = SINGLE_TAG_QUERY_REGEX.matchEntire(keyword.trim()) ?: return null
    val rawNs = match.groupValues[1].lowercase(Locale.ROOT)
    val rawTag = (match.groupValues[2].ifEmpty { match.groupValues[3] }).trim()
    val ns = TagNamespace.from(rawNs) ?: return null
    val translatedTag = translateTag(ns.prefix, rawTag)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val translatedNs = translateNs(rawNs)?.trim()?.takeIf { it.isNotEmpty() }
    return if (translatedNs != null) "$translatedNs：$translatedTag" else "#$translatedTag"
}

/**
 * 构造 E-Hentai 精准标签搜索表达式；当标签含空格时加双引号并以 `$` 结束确保精确命中。
 */
internal fun buildTagSearchQuery(namespace: String, rawTag: String): String {
    val ns = namespace.trim().lowercase(Locale.ROOT)
    val tag = rawTag.trim()
    return if (' ' in tag) {
        "$ns:\"$tag$\""
    } else {
        "$ns:$tag$"
    }
}

private fun formatDisplayTag(rawTag: String): String =
    rawTag.split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString() }
    }

private fun resolveDisplayTagTitle(
    namespace: String,
    rawTag: String,
    translateTag: (nsPrefix: String?, rawTag: String) -> String?,
): String {
    val nsPrefix = TagNamespace.from(namespace)?.prefix
    val translated = translateTag(nsPrefix, rawTag)?.trim()?.takeIf { it.isNotEmpty() }
    return if (translated != null && !translated.equals(rawTag, ignoreCase = true)) {
        "#$translated"
    } else {
        "#${formatDisplayTag(rawTag)}"
    }
}

/**
 * 依据最新词库或翻译开关状态，动态刷新推荐标签列表的显示标题，避免缓存把未译英文长期固化。
 */
internal fun refreshRecommendedTagTitles(
    items: List<RecommendedTagItem>,
    translateTag: (nsPrefix: String?, rawTag: String) -> String?,
): List<RecommendedTagItem> {
    var changed = false
    val refreshed = items.map { item ->
        val newTitle = resolveDisplayTagTitle(item.namespace, item.rawTag, translateTag)
        if (newTitle == item.displayTitle) {
            item
        } else {
            changed = true
            item.copy(displayTitle = newTitle)
        }
    }
    return if (changed) refreshed else items
}

private fun isTagAllowedForMode(
    namespace: String,
    rawTag: String,
    tabMode: RecommendTabMode,
    nonHMode: Boolean,
): Boolean {
    if (namespace.isBlank() || rawTag.isBlank()) return false
    if (namespace == "language" || namespace == "reclass" || namespace == "temp" || namespace == "location") {
        return false
    }
    if (isMaleRelatedTag(namespace, rawTag)) return false
    if (namespace == "other" && rawTag in NOISE_OTHER_TAGS) return false
    if (nonHMode && (namespace in setOf("female", "mixed") || rawTag in NON_H_BLOCKED_TAGS)) {
        return false
    }
    return when (tabMode) {
        RecommendTabMode.TAGS -> namespace in setOf("parody", "character", "female", "mixed", "other")
        RecommendTabMode.PARODIES -> namespace == "parody"
        RecommendTabMode.ARTISTS -> namespace in setOf("artist", "group")
    }
}

private fun namespaceWeight(namespace: String, tabMode: RecommendTabMode): Double = when (tabMode) {
    RecommendTabMode.TAGS -> when (namespace) {
        "parody" -> 1.35
        "character" -> 1.20
        "other" -> 1.15
        "female" -> 1.0
        "mixed" -> 0.95
        else -> 0.90
    }
    RecommendTabMode.PARODIES -> 1.25
    RecommendTabMode.ARTISTS -> when (namespace) {
        "artist" -> 1.15
        "group" -> 1.0
        else -> 0.9
    }
}

private data class TagCandidate(
    val tagKey: String,
    val namespace: String,
    val rawTag: String,
    val score: Double,
    val peakRating: Float,
    val galleries: List<BaseGalleryInfo>,
)

/**
 * 基于真实在线画廊列表动态提取、评分并去重的热门标签推荐核心纯算法：
 * 1. 优先过滤无封面画廊与低评分画廊，并在 Non-H 模式下强制只保留 `EhUtils.NON_H`；
 * 2. 按 `ln(1 + 出现次数)`、`平均星级`、`峰值星级高分加成` 与 `命名空间权重` 综合排序；
 * 3. 在多标签模式下控制单一命名空间占比不超过 55%，确保版面内容多样；
 * 4. 为每个入选标签优先绑定尚未被其他标签占用的高星真实画廊封面，避免“全屏同一张图”。
 */
internal fun extractRecommendedTags(
    galleries: List<BaseGalleryInfo>,
    tabMode: RecommendTabMode,
    nonHMode: Boolean,
    excludeTagKeys: Set<String> = emptySet(),
    usedCoverGids: Set<Long> = emptySet(),
    usedCoverThumbKeys: Set<String> = emptySet(),
    maxItems: Int = 18,
    translateTag: (namespacePrefix: String?, rawTag: String) -> String? = { _, _ -> null },
): RecommendedBatchResult {
    if (galleries.isEmpty() || maxItems <= 0) {
        return RecommendedBatchResult(emptyList(), emptySet(), emptySet(), emptySet())
    }

    val validPool = galleries.asSequence()
        .filter { !it.thumbKey.isNullOrBlank() }
        .filter { !nonHMode || it.category == EhUtils.NON_H }
        .distinctBy { it.gid }
        .toList()
    if (validPool.isEmpty()) {
        return RecommendedBatchResult(emptyList(), emptySet(), emptySet(), emptySet())
    }

    val highRatedOnly = validPool.filter { it.rating >= 3.5f }
    val workingPool = if (highRatedOnly.size >= 4) highRatedOnly else validPool

    val grouped = LinkedHashMap<String, MutableList<BaseGalleryInfo>>()
    for (gallery in workingPool) {
        val rawTags = gallery.simpleTags.orEmpty()
        val seenInGallery = HashSet<String>()
        for (entry in rawTags) {
            val colonIdx = entry.indexOf(':')
            if (colonIdx <= 0 || colonIdx >= entry.length - 1) continue
            val ns = entry.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
            val rawTag = entry.substring(colonIdx + 1).trim().lowercase(Locale.ROOT)
            if (!isTagAllowedForMode(ns, rawTag, tabMode, nonHMode)) continue
            val tagKey = "$ns:$rawTag"
            if (tagKey in excludeTagKeys || !seenInGallery.add(tagKey)) continue
            grouped.getOrPut(tagKey) { mutableListOf() }.add(gallery)
        }
    }

    if (grouped.isEmpty()) {
        return RecommendedBatchResult(emptyList(), emptySet(), emptySet(), emptySet())
    }

    val candidates = grouped.map { (tagKey, matchedGalleries) ->
        val colonIdx = tagKey.indexOf(':')
        val ns = tagKey.substring(0, colonIdx)
        val rawTag = tagKey.substring(colonIdx + 1)
        val freqScore = ln(1.0 + matchedGalleries.size)
        val avgRating = matchedGalleries.map { it.rating.coerceAtLeast(1f).toDouble() }.average()
        val peakRating = matchedGalleries.maxOf { it.rating }
        val highStarBoost = when {
            peakRating >= 4.5f -> 1.35
            peakRating >= 4.0f -> 1.15
            else -> 0.85
        }
        val weight = namespaceWeight(ns, tabMode)
        val score = (freqScore + 0.65) * (avgRating / 4.0) * highStarBoost * weight
        TagCandidate(
            tagKey = tagKey,
            namespace = ns,
            rawTag = rawTag,
            score = score,
            peakRating = peakRating,
            galleries = matchedGalleries.sortedWith(
                compareByDescending<BaseGalleryInfo> { it.rating }
                    .thenByDescending { it.pages }
                    .thenByDescending { it.gid },
            ),
        )
    }.sortedWith(
        compareByDescending<TagCandidate> { it.score }
            .thenByDescending { it.peakRating }
            .thenBy { it.tagKey },
    )

    val mutableUsedCoverGids = usedCoverGids.toMutableSet()
    val mutableUsedThumbKeys = usedCoverThumbKeys.toMutableSet()
    val newlyAddedCoverGids = HashSet<Long>()
    val newlyAddedThumbKeys = HashSet<String>()
    val newlyAddedTagKeys = HashSet<String>()

    fun pickUniqueCover(candidate: TagCandidate): BaseGalleryInfo? {
        fun isUnused(g: BaseGalleryInfo): Boolean {
            val key = g.thumbKey ?: return false
            return g.gid !in mutableUsedCoverGids && key !in mutableUsedThumbKeys
        }
        return candidate.galleries.firstOrNull { isUnused(it) && it.rating >= 4.0f }
            ?: candidate.galleries.firstOrNull { isUnused(it) }
    }

    // 多命名空间均衡挑选，且严格要求每个标签绑定互不重复的真实画廊封面（宁缺毋滥，绝不共用同一张图）
    val pickedPairs = mutableListOf<Pair<TagCandidate, BaseGalleryInfo>>()
    val deferredCandidates = mutableListOf<TagCandidate>()
    val nsCounts = HashMap<String, Int>()
    val maxPerNs = if (tabMode == RecommendTabMode.TAGS) {
        maxOf(2, ceil(maxItems * 0.55).toInt())
    } else {
        maxItems
    }

    for (candidate in candidates) {
        if (pickedPairs.size >= maxItems) break
        val currentNsCount = nsCounts.getOrDefault(candidate.namespace, 0)
        if (currentNsCount < maxPerNs) {
            val cover = pickUniqueCover(candidate) ?: continue
            cover.thumbKey?.let { mutableUsedThumbKeys.add(it); newlyAddedThumbKeys.add(it) }
            mutableUsedCoverGids.add(cover.gid)
            newlyAddedCoverGids.add(cover.gid)
            newlyAddedTagKeys.add(candidate.tagKey)
            pickedPairs.add(candidate to cover)
            nsCounts[candidate.namespace] = currentNsCount + 1
        } else {
            deferredCandidates.add(candidate)
        }
    }
    if (pickedPairs.size < maxItems) {
        for (candidate in deferredCandidates) {
            if (pickedPairs.size >= maxItems) break
            val cover = pickUniqueCover(candidate) ?: continue
            cover.thumbKey?.let { mutableUsedThumbKeys.add(it); newlyAddedThumbKeys.add(it) }
            mutableUsedCoverGids.add(cover.gid)
            newlyAddedCoverGids.add(cover.gid)
            newlyAddedTagKeys.add(candidate.tagKey)
            pickedPairs.add(candidate to cover)
        }
    }

    val items = pickedPairs.map { (candidate, cover) ->
        val displayTitle = resolveDisplayTagTitle(candidate.namespace, candidate.rawTag, translateTag)
        val subtitle = candidate.rawTag
        val (startArgb, endArgb) = GRADIENT_PALETTE[candidate.tagKey.hashCode().absoluteValue % GRADIENT_PALETTE.size]

        RecommendedTagItem(
            tagKey = candidate.tagKey,
            namespace = candidate.namespace,
            rawTag = candidate.rawTag,
            query = buildTagSearchQuery(candidate.namespace, candidate.rawTag),
            displayTitle = displayTitle,
            subtitle = subtitle,
            score = candidate.score,
            sampleCount = candidate.galleries.size,
            coverGallery = cover,
            gradientStartArgb = startArgb,
            gradientEndArgb = endArgb,
        )
    }

    return RecommendedBatchResult(
        items = items,
        addedTagKeys = newlyAddedTagKeys,
        addedCoverGids = newlyAddedCoverGids,
        addedCoverThumbKeys = newlyAddedThumbKeys,
    )
}

/**
 * 从 E-Hentai 线上高星池拉取一批真实画廊（每次最多 1 次列表请求 + 至多 1 次紧凑视图 API 补全，零 N+1 请求）。
 */
internal suspend fun fetchHighRatedGalleryBatch(
    sourceSeed: Int,
    nextCursor: String?,
    nextToplistPage: Int,
    usedToplist: Boolean,
    canFallbackNormal: Boolean,
    nonHMode: Boolean,
    isLoadMore: Boolean,
): HighRatedFetchResult {
    var activeNextToplistPage = nextToplistPage
    var activeUsedToplist = usedToplist
    var activeCanFallbackNormal = canFallbackNormal

    val builder = when {
        nonHMode -> {
            val minRating = if (!isLoadMore && sourceSeed % 3 == 2) 3 else 4
            ListUrlBuilder(
                mode = MODE_NORMAL,
                category = EhUtils.NON_H,
                minRating = minRating,
            ).apply {
                if (isLoadMore && !nextCursor.isNullOrBlank()) {
                    setIndex(nextCursor, isNext = true)
                } else if (!isLoadMore && sourceSeed > 0) {
                    val rangeJump = (sourceSeed * 17) % 70
                    if (rangeJump > 0) setRange(rangeJump)
                }
            }
        }
        isLoadMore && activeUsedToplist && activeNextToplistPage in 2..10 -> {
            ListUrlBuilder(
                mode = MODE_TOPLIST,
                keyword = "13",
            ).apply {
                page = activeNextToplistPage
                activeNextToplistPage += 1
            }
        }
        isLoadMore && !nextCursor.isNullOrBlank() -> {
            ListUrlBuilder(
                mode = MODE_NORMAL,
                minRating = 4,
            ).apply {
                setIndex(nextCursor, isNext = true)
            }
        }
        isLoadMore && activeCanFallbackNormal -> {
            // 热门页无分页游标，首次触底时无缝衔接高星普通榜首页
            activeCanFallbackNormal = false
            activeUsedToplist = false
            ListUrlBuilder(
                mode = MODE_NORMAL,
                minRating = 4,
            )
        }
        else -> {
            // 全新首屏或下拉刷新：轮换全站高星榜、热门榜、近月高星排行与深度跳页
            when (sourceSeed.mod(4)) {
                0 -> {
                    activeUsedToplist = false
                    activeCanFallbackNormal = false
                    ListUrlBuilder(mode = MODE_NORMAL, minRating = 4)
                }
                1 -> {
                    activeUsedToplist = false
                    activeCanFallbackNormal = true
                    ListUrlBuilder(mode = MODE_WHATS_HOT)
                }
                2 -> {
                    activeUsedToplist = true
                    activeCanFallbackNormal = false
                    activeNextToplistPage = 2
                    ListUrlBuilder(mode = MODE_TOPLIST, keyword = "13")
                }
                else -> {
                    activeUsedToplist = false
                    activeCanFallbackNormal = false
                    val rangeJump = 15 + ((sourceSeed / 4) * 19) % 65
                    ListUrlBuilder(mode = MODE_NORMAL, minRating = 4).apply {
                        setRange(rangeJump)
                    }
                }
            }
        }
    }

    val requestUrl = builder.build()
    val result = EhEngine.getGalleryList(requestUrl)
    val list = result.galleryInfoList

    // 若用户网站端开启 Compact 等无标签视图，单次批量 API 补全标签与星级，避免逐个请求详情
    val missingTags = list.count { it.simpleTags.isNullOrEmpty() }
    if (list.isNotEmpty() && missingTags * 2 >= list.size) {
        EhEngine.fillGalleryListByApi(list.take(25), requestUrl)
    }

    val hasMore = when {
        activeUsedToplist -> activeNextToplistPage <= 10
        !result.next.isNullOrBlank() -> true
        activeCanFallbackNormal -> true
        else -> false
    }

    return HighRatedFetchResult(
        galleries = list,
        nextCursor = result.next,
        nextToplistPage = activeNextToplistPage,
        usedToplist = activeUsedToplist,
        canFallbackNormal = activeCanFallbackNormal,
        hasMore = hasMore,
    )
}

/**
 * 推荐结果轻量磁盘缓存（SWR 30 分钟 TTL）：二次进页面先瞬时出缓存，避免白屏等待。
 */
internal object ExperimentalRecommendationCache {
    private const val CACHE_TTL_MILLIS = 30 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    private fun cacheFile(context: Context, site: Int, nonHMode: Boolean, tabMode: RecommendTabMode): File =
        File(context.cacheDir, "exp_search_recs_v4_${site}_${if (nonHMode) 1 else 0}_${tabMode.name}.json")

    suspend fun load(
        context: Context,
        site: Int,
        nonHMode: Boolean,
        tabMode: RecommendTabMode,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<List<RecommendedTagItem>, Boolean>? = withIOContext {
        runCatching {
            val file = cacheFile(context, site, nonHMode, tabMode)
            if (!file.exists()) return@withIOContext null
            val payload = json.decodeFromString<CachedRecommendations>(file.readText())
            if (payload.items.isEmpty()) return@withIOContext null
            val isFresh = (nowMillis - payload.timestampMillis) in 0L..CACHE_TTL_MILLIS
            payload.items to isFresh
        }.getOrNull()
    }

    suspend fun save(
        context: Context,
        site: Int,
        nonHMode: Boolean,
        tabMode: RecommendTabMode,
        items: List<RecommendedTagItem>,
        nowMillis: Long = System.currentTimeMillis(),
    ) = withIOContext {
        if (items.isEmpty()) return@withIOContext
        runCatching {
            val file = cacheFile(context, site, nonHMode, tabMode)
            val payload = CachedRecommendations(timestampMillis = nowMillis, items = items.take(36))
            file.writeText(json.encodeToString(payload))
        }
        Unit
    }
}

/**
 * 在实验模式下待机空闲时静默预热推荐标签列表与前 6 张封面进磁盘缓存，消除首次点开搜索的等待时间。
 */
internal object ExperimentalSearchPrewarmer {
    private val mutex = Mutex()

    @Volatile
    private var lastKey: String? = null

    suspend fun prewarm(context: Context, site: Int, nonHMode: Boolean) = withIOContext {
        val key = "${site}_$nonHMode"
        mutex.withLock {
            if (lastKey == key) return@withIOContext
            val existing = ExperimentalRecommendationCache.load(context, site, nonHMode, RecommendTabMode.TAGS)
            if (existing != null && existing.second) {
                lastKey = key
                return@withIOContext
            }
            try {
                val batch = fetchHighRatedGalleryBatch(
                    sourceSeed = 0,
                    nextCursor = null,
                    nextToplistPage = 2,
                    usedToplist = false,
                    canFallbackNormal = false,
                    nonHMode = nonHMode,
                    isLoadMore = false,
                )
                val extracted = extractRecommendedTags(
                    galleries = batch.galleries,
                    tabMode = RecommendTabMode.TAGS,
                    nonHMode = nonHMode,
                    maxItems = 16,
                    translateTag = { nsPrefix, rawTag ->
                        val canTranslate = Settings.showTagTranslations.value &&
                            with(context) { EhTagDatabase.translatable } &&
                            EhTagDatabase.initialized
                        if (canTranslate) {
                            EhTagDatabase.getTranslation(nsPrefix, rawTag)
                        } else {
                            null
                        }
                    },
                )
                if (extracted.items.isNotEmpty()) {
                    ExperimentalRecommendationCache.save(context, site, nonHMode, RecommendTabMode.TAGS, extracted.items)
                    lastKey = key
                    val loader = context.imageLoader
                    extracted.items.take(6).chunked(2).forEach { chunk ->
                        coroutineScope {
                            chunk.map { item ->
                                async {
                                    try {
                                        loader.execute(ImageRequest.Builder(context).ehUrl(item.coverGallery).build())
                                    } catch (ce: CancellationException) {
                                        throw ce
                                    } catch (_: Throwable) {
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
        }
    }
}
