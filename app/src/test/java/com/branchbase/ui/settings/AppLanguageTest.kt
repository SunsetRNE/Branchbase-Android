package com.branchbase.ui.settings

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 语言页的纯逻辑钉子（`ui/settings/AppLanguage.kt` 里不碰 Android 的那几个函数）。
 *
 * 这些断言刻意**不比对具体译文**（「中文」还是「简体中文」取决于 JDK 的 CLDR 版本，
 * 钉死它等于给自己埋一个跟 JDK 升级一起红的假红），只钉**关系**：
 * 名称用母语自称、说明用当前界面语言的他称、两者相同时不给说明。
 */
class AppLanguageTest {

    private val zh = Locale.SIMPLIFIED_CHINESE
    private val en = Locale.ENGLISH

    // ── 名称：母语自称 ──────────────────────────────────────────────────

    @Test
    fun `名称用母语自称而不是当前界面语言`() {
        // 英文在任何界面语言下都叫 English —— 界面已经是看不懂的语言时，
        // 用户也得能认出自己那一行，这是语言选择器的通行做法
        assertEquals("English", languageOptionName(en))
        // 中文的自称随 CLDR 可能是「中文」或「简体中文」，所以只钉「不随界面语言变」这条性质
        assertEquals(
            languageOptionName(zh),
            zh.getDisplayName(zh),
        )
    }

    // ── 说明：当前界面语言的他称 ────────────────────────────────────────

    @Test
    fun `说明用当前界面语言的他称`() {
        // 界面是中文时，English 那行的说明应当不是 "English"（那就是复述名称了）
        val desc = languageOptionDesc(en, zh)
        assertNotNull("界面中文时 English 那行必须有说明", desc)
        assertEquals(en.getDisplayName(zh), desc)
    }

    @Test
    fun `名称与说明相同时不给说明`() {
        // 界面语言 == 该行语言：这一行就是用户正在读的语言，再写一遍就是复述名称（规范 §6.2）
        assertNull("界面英文时 English 那行不该有说明", languageOptionDesc(en, en))
    }

    // ── 值列 ────────────────────────────────────────────────────────────

    @Test
    fun `值列在跟随系统时用规范用语`() {
        assertEquals(
            "跟随系统",
            languageRowValue(LANGUAGE_FOLLOW_SYSTEM, listOf(zh, en), followLabel = "跟随系统"),
        )
    }

    @Test
    fun `值列与语言页用同一个名称`() {
        // 同一含义在整棵树里只有一种写法：值列不能写「英语」而列表里写 English
        assertEquals(
            languageOptionName(en),
            languageRowValue(en.toLanguageTag(), listOf(zh, en), followLabel = "跟随系统"),
        )
    }

    @Test
    fun `值列认不出标签时回落到跟随系统而不是显示裸标签`() {
        // 清单变了、或系统还原了一个本包没有的语言：值列不该出现 en-US 这种东西
        assertEquals(
            "跟随系统",
            languageRowValue("fr-FR", listOf(zh, en), followLabel = "跟随系统"),
        )
    }
}
