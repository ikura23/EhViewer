package com.hippo.ehviewer.ui.experimental

import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.client.EhUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalTagRecommenderTest {

    private fun fakeGallery(
        gid: Long,
        rating: Float,
        category: Int = EhUtils.DOUJINSHI,
        tags: List<String>,
        thumbKey: String? = "thumb/$gid.jpg",
    ) = BaseGalleryInfo(
        gid = gid,
        token = "tok_$gid",
        title = "Gallery $gid",
        thumbKey = thumbKey,
        category = category,
        rating = rating,
        pages = 30,
        simpleTags = tags,
    )

    @Test
    fun `extractRecommendedTags ranks high rated popular tags first instead of hardcoding BlueArchive`() {
        val pool = listOf(
            fakeGallery(1, 4.9f, tags = listOf("parody:touhou project", "other:full color", "language:chinese")),
            fakeGallery(2, 4.8f, tags = listOf("parody:touhou project", "character:reimu hakurei")),
            fakeGallery(3, 4.7f, tags = listOf("parody:touhou project", "other:full color")),
            fakeGallery(4, 3.6f, tags = listOf("parody:blue archive", "other:sample")),
            fakeGallery(5, 4.5f, tags = listOf("artist:as109", "other:full color")),
        )

        val result = extractRecommendedTags(
            galleries = pool,
            tabMode = RecommendTabMode.TAGS,
            nonHMode = false,
            translateTag = { ns, tag ->
                if (ns == "p" && tag == "touhou project") "东方Project" else null
            },
        )

        assertTrue(result.items.isNotEmpty())
        val first = result.items.first()
        assertEquals("parody:touhou project", first.tagKey)
        assertEquals("#东方Project", first.displayTitle)
        assertEquals("parody:\"touhou project$\"", first.query)
        // 验证 other:sample 等噪点以及 language:* 均已被剔除
        assertFalse(result.items.any { it.tagKey == "other:sample" || it.namespace == "language" })
    }

    @Test
    fun `extractRecommendedTags picks highest rated unique real cover gallery per tag`() {
        val pool = listOf(
            fakeGallery(101, 4.2f, tags = listOf("parody:genshin impact", "other:full color")),
            fakeGallery(102, 4.9f, tags = listOf("parody:genshin impact")),
            fakeGallery(103, 4.6f, tags = listOf("other:full color", "character:furina")),
            fakeGallery(104, 4.8f, tags = listOf("character:furina")),
            // 缺少 thumbKey 的画廊不可被选中作封面
            fakeGallery(105, 5.0f, tags = listOf("parody:genshin impact"), thumbKey = null),
        )

        val result = extractRecommendedTags(
            galleries = pool,
            tabMode = RecommendTabMode.TAGS,
            nonHMode = false,
        )

        val genshin = result.items.first { it.tagKey == "parody:genshin impact" }
        assertEquals(102L, genshin.coverGallery.gid)
        // 每个推荐卡片尽量分配不重复的真实画廊 gid
        val distinctGids = result.items.map { it.coverGallery.gid }.toSet()
        assertEquals(result.items.size, distinctGids.size)
    }

    @Test
    fun `extractRecommendedTags deduplicates across refresh and loadMore via excludeTagKeys`() {
        val pool = listOf(
            fakeGallery(1, 4.8f, tags = listOf("parody:alpha", "character:c1", "other:full color")),
            fakeGallery(2, 4.7f, tags = listOf("parody:alpha", "parody:beta", "character:c2")),
            fakeGallery(3, 4.6f, tags = listOf("parody:beta", "character:c3")),
            fakeGallery(4, 4.5f, tags = listOf("parody:gamma", "other:animated")),
        )

        val firstBatch = extractRecommendedTags(
            galleries = pool,
            tabMode = RecommendTabMode.TAGS,
            nonHMode = false,
            maxItems = 3,
        )
        assertEquals(3, firstBatch.items.size)

        val secondBatch = extractRecommendedTags(
            galleries = pool,
            tabMode = RecommendTabMode.TAGS,
            nonHMode = false,
            excludeTagKeys = firstBatch.addedTagKeys,
            usedCoverGids = firstBatch.addedCoverGids,
            maxItems = 10,
        )
        assertTrue(secondBatch.items.isNotEmpty())
        assertTrue(secondBatch.items.none { it.tagKey in firstBatch.addedTagKeys })
    }

    @Test
    fun `extractRecommendedTags strictly filters to Non-H category when nonHMode is enabled`() {
        val pool = listOf(
            fakeGallery(
                gid = 201,
                rating = 4.95f,
                category = EhUtils.DOUJINSHI,
                tags = listOf("parody:adult parody", "female:sole female", "other:uncensored"),
            ),
            fakeGallery(
                gid = 202,
                rating = 4.4f,
                category = EhUtils.NON_H,
                tags = listOf("parody:wholesome series", "other:full color", "female:school uniform"),
            ),
            fakeGallery(
                gid = 203,
                rating = 4.6f,
                category = EhUtils.NON_H,
                tags = listOf("parody:wholesome series", "character:heroine"),
            ),
        )

        val result = extractRecommendedTags(
            galleries = pool,
            tabMode = RecommendTabMode.TAGS,
            nonHMode = true,
        )

        assertTrue(result.items.isNotEmpty())
        assertTrue(result.items.all { it.coverGallery.category == EhUtils.NON_H })
        assertFalse(result.items.any { it.tagKey == "parody:adult parody" })
        assertFalse(result.items.any { it.namespace == "female" || it.rawTag == "uncensored" })
        assertTrue(result.items.any { it.tagKey == "parody:wholesome series" })
    }

    @Test
    fun `extractRecommendedTags separates PARODIES and ARTISTS namespaces and formats exact query`() {
        val pool = listOf(
            fakeGallery(1, 4.8f, tags = listOf("parody:original", "artist:fkey", "group:team alpha", "other:cg")),
            fakeGallery(2, 4.7f, tags = listOf("parody:touhou project", "artist:fkey", "character:marisa")),
            fakeGallery(3, 4.6f, tags = listOf("group:team alpha", "character:alice")),
            fakeGallery(4, 4.5f, tags = listOf("parody:original", "artist:rurudo")),
        )

        val parodies = extractRecommendedTags(pool, RecommendTabMode.PARODIES, nonHMode = false)
        assertTrue(parodies.items.isNotEmpty())
        assertTrue(parodies.items.all { it.namespace == "parody" })
        assertFalse(parodies.items.any { it.namespace == "character" })

        val artists = extractRecommendedTags(pool, RecommendTabMode.ARTISTS, nonHMode = false)
        assertTrue(artists.items.isNotEmpty())
        assertTrue(artists.items.all { it.namespace in setOf("artist", "group") })
        assertEquals("artist:fkey$", buildTagSearchQuery("artist", "fkey"))
        assertEquals("group:\"team alpha$\"", buildTagSearchQuery("group", "team alpha"))
    }

    @Test
    fun `extractRecommendedTags blocks male tags and strips rating stars from subtitle`() {
        val pool = listOf(
            fakeGallery(
                gid = 1,
                rating = 4.9f,
                tags = listOf(
                    "male:sole male",
                    "male:shotacon",
                    "mixed:males only",
                    "other:yaoi",
                    "female:crossdressing",
                    "female:school uniform",
                    "parody:blue archive",
                ),
            ),
            fakeGallery(
                gid = 2,
                rating = 4.7f,
                tags = listOf("female:school uniform", "parody:blue archive", "other:full color"),
            ),
        )

        val result = extractRecommendedTags(
            galleries = pool,
            tabMode = RecommendTabMode.TAGS,
            nonHMode = false,
            maxItems = 10,
        )

        assertTrue(result.items.isNotEmpty())
        assertFalse(result.items.any { it.namespace == "male" })
        assertFalse(result.items.any { it.rawTag in setOf("sole male", "shotacon", "males only", "yaoi", "crossdressing") })
        // female:school uniform 等正常女性/作品 tag 不受影响
        assertTrue(result.items.any { it.tagKey == "female:school uniform" })
        assertTrue(result.items.any { it.tagKey == "parody:blue archive" })
        // subtitle 中不含星星等级标注
        assertTrue(result.items.none { '★' in it.subtitle })
    }

    @Test
    fun `extractRecommendedTags strictly forbids reusing cover gid or thumbKey across tags`() {
        val pool = listOf(
            fakeGallery(
                gid = 1,
                rating = 4.9f,
                tags = listOf("parody:p1", "parody:p2", "parody:p3", "character:c1", "other:full color"),
                thumbKey = "shared/thumb_a.jpg",
            ),
            fakeGallery(
                gid = 2,
                rating = 4.8f,
                tags = listOf("parody:p1", "parody:p2", "character:c2", "other:animated"),
                thumbKey = "shared/thumb_b.jpg",
            ),
        )

        val result = extractRecommendedTags(
            galleries = pool,
            tabMode = RecommendTabMode.TAGS,
            nonHMode = false,
            maxItems = 10,
        )

        // 池中只有 2 张不同封面，绝不能出现第 3 个或更多复用同一封面的推荐卡片
        assertEquals(2, result.items.size)
        val gids = result.items.map { it.coverGallery.gid }.toSet()
        val thumbKeys = result.items.mapNotNull { it.coverGallery.thumbKey }.toSet()
        assertEquals(2, gids.size)
        assertEquals(2, thumbKeys.size)
    }

    @Test
    fun `extractRecommendedTags blocks maintenance noise tags and formatSingleTagTitle translates single query`() {
        val pool = listOf(
            fakeGallery(
                gid = 1,
                rating = 4.9f,
                tags = listOf(
                    "other:already uploaded",
                    "other:rough translation",
                    "other:machine translated",
                    "other:full color",
                ),
            ),
            fakeGallery(
                gid = 2,
                rating = 4.8f,
                tags = listOf("parody:blue archive", "other:full color"),
            ),
        )
        val result = extractRecommendedTags(pool, RecommendTabMode.TAGS, nonHMode = false)
        assertFalse(result.items.any { it.rawTag in setOf("already uploaded", "rough translation", "machine translated") })
        assertTrue(result.items.any { it.tagKey == "other:full color" })

        val translated = formatSingleTagTitle(
            keyword = "other:\"multi-work series$\"",
            translateNs = { ns -> if (ns == "other") "其他" else null },
            translateTag = { pfx, tag -> if (pfx == "o" && tag == "multi-work series") "系列作品" else null },
        )
        assertEquals("其他：系列作品", translated)

        val multiKeyword = formatSingleTagTitle(
            keyword = "parody:\"blue archive$\" other:animated$",
            translateNs = { it },
            translateTag = { _, t -> t },
        )
        assertNull(multiKeyword)

        // 无引号单词标签正确翻译，未命中词库则返回 null 回退原词
        val singleWordTranslated = formatSingleTagTitle(
            keyword = "female:glasses$",
            translateNs = { ns -> if (ns == "female") "女性" else null },
            translateTag = { pfx, tag -> if (pfx == "f" && tag == "glasses") "眼镜" else null },
        )
        assertEquals("女性：眼镜", singleWordTranslated)

        val unmapped = formatSingleTagTitle(
            keyword = "female:glasses$",
            translateNs = { "女性" },
            translateTag = { _, _ -> null },
        )
        assertNull(unmapped)
    }

    @Test
    fun `refreshRecommendedTagTitles updates cached titles dynamically when translation state changes`() {
        val pool = listOf(
            fakeGallery(gid = 1, rating = 4.9f, tags = listOf("female:glasses", "other:full color")),
            fakeGallery(gid = 2, rating = 4.8f, tags = listOf("female:glasses")),
        )
        // 模拟启动初期词库尚未初始化：先生成英文标题的缓存条目
        val englishCached = extractRecommendedTags(
            galleries = pool,
            tabMode = RecommendTabMode.TAGS,
            nonHMode = false,
            translateTag = { _, _ -> null },
        ).items
        assertTrue(englishCached.any { it.displayTitle == "#Glasses" })

        // 词库就绪后，动态补译为中文标题
        val zhUpdated = refreshRecommendedTagTitles(englishCached) { pfx, tag ->
            when (pfx to tag) {
                "f" to "glasses" -> "眼镜"
                "o" to "full color" -> "全彩"
                else -> null
            }
        }
        assertTrue(zhUpdated !== englishCached)
        assertTrue(zhUpdated.any { it.tagKey == "female:glasses" && it.displayTitle == "#眼镜" })

        // 若再次执行相同翻译则返回原列表引用，避免无意义 UI 重组
        val unchanged = refreshRecommendedTagTitles(zhUpdated) { pfx, tag ->
            when (pfx to tag) {
                "f" to "glasses" -> "眼镜"
                "o" to "full color" -> "全彩"
                else -> null
            }
        }
        assertTrue(unchanged === zhUpdated)

        // 若用户关闭标签翻译，则重新回退到规范 TitleCase 英文标题
        val reverted = refreshRecommendedTagTitles(zhUpdated) { _, _ -> null }
        assertTrue(reverted.any { it.tagKey == "female:glasses" && it.displayTitle == "#Glasses" })
    }
}
