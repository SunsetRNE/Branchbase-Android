package com.branchbase.ui.repository

import android.content.Context

/**
 * 沉浸式翻译的用户设置。
 *
 * ## 为什么设置放在 repository 包而不是 profile 包
 *
 * 设置的**消费方**是 `ReadmeWebView`（正文渲染）与 `Translator`（翻译执行），
 * 二者都在本包；设置页只是「写」的一方。放在消费方这一侧，页面无需反向依赖 UI 层的设置屏。
 *
 * ## 三个字段各自的取舍
 *
 * - [enabled]：**默认关**。开启意味着打开任意正文页都会自动发翻译请求（消耗免费额度、
 *   且在弱网下拖慢首屏）；默认关 + 页内右下角「译」按钮手动触发，把决定权交给用户；
 * - [target]：只做中英两向（[source] 由目标反推），不引入语言选择器的复杂度 ——
 *   这个 App 的用户场景就是「读英文仓库 → 看中文」；
 * - [dual]：对照（原文在上、译文在下）是「沉浸式翻译」的默认读法；
 *   仅译文适合小屏快速浏览，两者共用同一份译文缓存。
 */
data class TranslateConfig(
    val enabled: Boolean = false,
    val target: String = ZH,
    val dual: Boolean = true,
) {
    val targetLabel: String get() = if (target == ZH) "中文（简体）" else "English"

    /** 源语言：由目标语言反推（目前只支持中英互译）。 */
    val source: String get() = if (target == ZH) EN else ZH

    companion object {
        const val EN = "en"
        const val ZH = "zh-CN"
    }
}

/** 设置的读写（SharedPreferences，与全局其它开关同用 `branchbase` 这份）。 */
object TranslateSettings {

    private const val KEY_ENABLED = "translate.enabled"
    private const val KEY_TARGET = "translate.target"
    private const val KEY_DUAL = "translate.dual"

    fun read(context: Context): TranslateConfig {
        val p = context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
        return TranslateConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            target = p.getString(KEY_TARGET, TranslateConfig.ZH) ?: TranslateConfig.ZH,
            dual = p.getBoolean(KEY_DUAL, true),
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) = write(context, KEY_ENABLED, enabled)

    fun setTarget(context: Context, target: String) = write(context, KEY_TARGET, target)

    fun setDual(context: Context, dual: Boolean) = write(context, KEY_DUAL, dual)

    fun reset(context: Context) {
        context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ENABLED).remove(KEY_TARGET).remove(KEY_DUAL)
            .apply()
    }

    /**
     * 注入到正文页的 JS 配置（`window.__bbTranslate`）。
     *
     * 走注入而不是让 JS 反问原生：页面脚本在 `loadDataWithBaseURL` 时就跑，
     * 那时没有可用的同步通道问「设置是什么」；而翻译开关必须在首帧前就位，
     * 否则会出现「先按关闭渲染、再跳成开启」的闪动。
     */
    fun jsConfig(config: TranslateConfig): String =
        "window.__bbTranslate = {enabled: ${config.enabled}, to: '${config.target}', dual: ${config.dual}};"

    private fun write(context: Context, key: String, value: Any) {
        val e = context.getSharedPreferences("branchbase", Context.MODE_PRIVATE).edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is String -> e.putString(key, value)
        }
        e.apply()
    }
}
