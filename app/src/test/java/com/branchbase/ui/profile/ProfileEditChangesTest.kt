package com.branchbase.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「哪些字段有变化」单测。
 *
 * 这条判据同时管两件事，所以必须钉住：
 * - `save()` 的 **PATCH body**（只提交变化字段，未改动的资料不能被覆盖）；
 * - 返回时的**未保存确认**（改回原值 = 没改，不该拦用户）。
 *
 * 旧写法是六行 `if (name != field("name")) body.put(...)` —— 两个用途各写一遍，
 * 后来加了「返回弹窗」时很容易只改一处，于是「改回原值仍弹未保存」或
 * 「未改动的字段被提交」这类缺陷都不会红。
 */
class ProfileEditChangesTest {

    private val original = mapOf(
        "name" to "SunsetRNE",
        "bio" to "",
        "company" to "",
        "location" to "Shanghai",
        "blog" to "",
        "twitter_username" to "",
    )

    @Test
    fun `没有改动时返回空`() {
        assertEquals(emptyList<String>(), changedProfileFields(original, original))
    }

    @Test
    fun `改回原值不算未保存`() {
        val edited = original + ("location" to "Beijing")
        val reverted = edited + ("location" to "Shanghai")
        assertEquals(emptyList<String>(), changedProfileFields(reverted, original))
    }

    @Test
    fun `只返回变化字段且顺序稳定`() {
        val current = original + mapOf(
            "twitter_username" to "SunsetRNE",
            "name" to "Sunset",
            "bio" to "hi",
        )
        // 顺序取 PROFILE_FIELDS（name, bio, company, location, blog, twitter_username），
        // 不随 map 的迭代顺序变化 —— PATCH body 的字段顺序因此是稳定的。
        assertEquals(listOf("name", "bio", "twitter_username"), changedProfileFields(current, original))
    }

    @Test
    fun `空值也算改动——清空简介要能提交`() {
        val current = original + ("bio" to "")
        val withBio = original + ("bio" to "hello")
        assertEquals(listOf("bio"), changedProfileFields(current, withBio))
        assertEquals(listOf("bio"), changedProfileFields(withBio, original))
    }

    @Test
    fun `缺失的键按空串比较`() {
        // 两侧都缺、或一侧缺但原值是空串：都不是改动
        assertEquals(emptyList<String>(), changedProfileFields(original - "bio", original))
        assertEquals(emptyList<String>(), changedProfileFields(original, original - "bio"))
        // 原值有内容、当前值缺键 = 把这一项清空了，是改动
        assertEquals(listOf("location"), changedProfileFields(original - "location", original))
    }
}
