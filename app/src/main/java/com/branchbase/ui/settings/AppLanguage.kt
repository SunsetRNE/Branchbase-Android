package com.branchbase.ui.settings

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * 应用内语言（设置 → 外观 → 语言）—— **系统 API 的薄封装 + 纯函数**。
 *
 * ## 为什么应用侧不存语言
 *
 * 这份实现**没有任何 prefs 键、没有 DataStore、没有内存里的语言状态**：语言存在系统里
 * （API 33+ 的 `LocaleManager`），App 每次现读。这样做的收益不是省几行代码，而是
 * **消灭一整类闪烁**——不存在「异步读出来才知道该用哪种语言」，也就不存在「首帧先渲染
 * 中文再跳英文」的窗口。代价是 33 以下没有这个能力，于是整行不出现（见 [languagePickerAvailable]）。
 *
 * ## 为什么语言清单也不在 App 里
 *
 * 清单来自 `android:localeConfig`，而它由 AGP 按 `values-*` 资源目录生成
 * （`app/build.gradle.kts` 的 `androidResources.generateLocaleConfig`）。
 * 于是「加一种语言」＝「加一个 `values-xx/` 目录」，这一页**自动多一个选项**，
 * 不需要改这里、也不需要改任何列表常量。
 */

/**
 * 语言入口的最低系统版本。
 *
 * `LocaleManager`（读写应用语言）与 `LocaleConfig`（读可用语言清单）都是 API 33 引入的。
 * 低于 33 时用户**没有任何办法**在 App 内换语言（去系统设置也没有这个开关），
 * 所以语言行整行不出现，而不是置灰 —— 规范 §3.2.1 对「仓库凭据」用的是同一条道理：
 * 禁用行必须给「怎么才能开」的出路，而这里的出路是换一台新系统的手机，不属于设置页能代办的事。
 */
const val LANGUAGE_PICKER_MIN_SDK: Int = Build.VERSION_CODES.TIRAMISU

/**
 * 表示「跟随系统」的哨兵值。
 *
 * 用空串而不是某个具体语言标签：`LocaleManager` 对「跟随系统」的表达就是**空的 LocaleList**，
 * 这里只是把那个语义原样搬过来，不引入第二套表示。
 */
const val LANGUAGE_FOLLOW_SYSTEM: String = ""

/**
 * 语言入口该不该出现。
 *
 * 两个条件缺一不可：
 * 1. API 33+ —— 低于 33 没有 `LocaleManager`；
 * 2. 清单里至少两种语言 —— 只有一种语言时，这一页没有任何可选项，
 *    摆出来只是让用户点进去看见一行自己。
 *
 * 第 2 条同时是**翻译完成度的门控**：`values-en/` 不存在时清单里就只有默认语言，
 * 入口自动隐藏，不会出现「切过去发现还是中文」的空壳功能。
 */
fun languagePickerAvailable(context: Context): Boolean =
    Build.VERSION.SDK_INT >= LANGUAGE_PICKER_MIN_SDK &&
        supportedAppLanguages(context).size >= 2

/**
 * 清单里支持的语言（顺序由 `locales_config.xml` 决定）。
 *
 * 33 以下返回空列表 —— 调用方只会在入口可见时用到它，而入口在 33 以下不可见。
 */
fun supportedAppLanguages(context: Context): List<Locale> =
    if (Build.VERSION.SDK_INT >= LANGUAGE_PICKER_MIN_SDK) {
        supportedAppLanguages33(context)
    } else {
        emptyList()
    }

@RequiresApi(LANGUAGE_PICKER_MIN_SDK)
private fun supportedAppLanguages33(context: Context): List<Locale> = try {
    // supportedLocales 可空：manifest 里没挂 android:localeConfig 时就是 null
    val list = LocaleConfig(context).supportedLocales
    if (list == null) emptyList() else (0 until list.size()).map { list[it] }
} catch (_: Throwable) {
    // 清单缺失或损坏时退化成「没有可选语言」，让入口自然隐藏 —— 比让设置页崩掉合理
    emptyList()
}

/** 当前应用语言标签；[LANGUAGE_FOLLOW_SYSTEM]（空串）表示跟随系统。 */
fun currentAppLanguageTag(context: Context): String =
    if (Build.VERSION.SDK_INT >= LANGUAGE_PICKER_MIN_SDK) {
        currentAppLanguageTag33(context)
    } else {
        LANGUAGE_FOLLOW_SYSTEM
    }

@RequiresApi(LANGUAGE_PICKER_MIN_SDK)
private fun currentAppLanguageTag33(context: Context): String {
    val manager = context.getSystemService(LocaleManager::class.java) ?: return LANGUAGE_FOLLOW_SYSTEM
    // 空 LocaleList（而不是 null）就是「跟随系统」—— 不是「没有语言」。
    // 别在这里回落成系统语言标签，否则「跟随系统」这一档永远选不中（选中态靠标签相等判断）
    val locales = manager.applicationLocales
    return if (locales.isEmpty) LANGUAGE_FOLLOW_SYSTEM else locales[0].toLanguageTag()
}

/**
 * 切换应用语言。返回是否真的写进去了。
 *
 * 失败时**不改 UI 选中态**：让「点了没反应」和「点了没生效」看起来一样，
 * 比先勾上再弹回去诚实（后者会让人以为设置成功了）。
 *
 * 注意这里**不重建 Activity**：`MainActivity` 声明了
 * `configChanges="locale|layoutDirection"`（见 AndroidManifest），
 * 框架会把新 Configuration 应用到 Activity 的 Resources，Compose 侧
 * `LocalConfiguration` 跟着更新、`stringResource` 自动取到新文案 ——
 * 导航记忆、各页取数状态、WebView 滚动位置都不会丢。
 */
fun setAppLanguage(context: Context, tag: String): Boolean =
    if (Build.VERSION.SDK_INT >= LANGUAGE_PICKER_MIN_SDK) {
        setAppLanguage33(context, tag)
    } else {
        false
    }

@RequiresApi(LANGUAGE_PICKER_MIN_SDK)
private fun setAppLanguage33(context: Context, tag: String): Boolean = try {
    val manager = context.getSystemService(LocaleManager::class.java)
    if (manager == null) {
        false
    } else {
        manager.applicationLocales =
            if (tag == LANGUAGE_FOLLOW_SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(tag)
            }
        true
    }
} catch (_: Throwable) {
    false
}

/** 当前系统语言（用于「跟随系统」那一行的说明）。 */
fun systemLanguage(context: Context): Locale {
    val locales = context.resources.configuration.locales
    return if (locales.size() > 0) locales[0] else Locale.getDefault()
}

// ───────────────────────── 显示名（纯函数，可单测） ─────────────────────────

/**
 * 语言行的**名称用母语自称**（endonym）：`English` 而不是「英语」。
 *
 * 用户在看不懂当前界面语言时，也必须能认出自己那一行 —— 这是语言选择器的通行做法，
 * 反过来（全部用当前界面语言的他称）会让「界面已经是看不懂的语言」的用户彻底卡死。
 */
internal fun languageOptionName(locale: Locale): String = locale.getDisplayName(locale)

/**
 * 语言行的**说明用当前界面语言的他称**（exonym）：界面是中文时，`English` 那行的说明写「英语」。
 *
 * 与名称相同时返回 `null` —— 那种情况只出现在「这一行就是你正在读的语言」，
 * 你已经认得它了，再写一遍就是用说明复述名称（规范 §6.2 明令禁止）。
 */
internal fun languageOptionDesc(locale: Locale, uiLocale: Locale): String? {
    val exonym = locale.getDisplayName(uiLocale)
    return exonym.takeIf { it != languageOptionName(locale) }
}

/**
 * 设置页「语言」行的**值列**文案（纯函数，便于单测）。
 *
 * - 跟随系统 → [followLabel]（规范 §6.1 逐字规定为「跟随系统」）；
 * - 指定语言 → 该语言的**母语自称**（`English`），与语言页里的名称保持一致：
 *   同一含义在整棵树里只有一种写法，值列不该出现「英语」而列表里写 `English`；
 * - 标签在清单里找不到（清单变了、或系统还原了一个本包没有的语言）→ 回落到 [followLabel]，
 *   而不是把裸语言标签（`en-US`）丢到值列上。
 */
internal fun languageRowValue(
    tag: String,
    supported: List<Locale>,
    followLabel: String,
): String {
    if (tag == LANGUAGE_FOLLOW_SYSTEM) return followLabel
    return supported.firstOrNull { it.toLanguageTag() == tag }
        ?.let { languageOptionName(it) }
        ?: followLabel
}
