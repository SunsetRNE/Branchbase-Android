package com.branchbase.translate

import android.content.Context

/**
 * 悬浮球面板上的**快捷设置**（页面 → 原生唯一的写入通道）。
 *
 * ## 为什么要有这一层
 *
 * 面板上的开关（显示方式 / 译文样式 / 目标语言 / 自动翻译 / 本地缓存 / 保护代码与链接）
 * 如果只记在页面里（localStorage），就会出现「面板里是仅译文、设置页里是对照」的双真源；
 * 用户改完设置页再回来，配置还会被页面里的旧值盖掉。因此这些键改动后立刻回写
 * SharedPreferences —— 设置页与正文页永远读同一份配置。
 *
 * ## 白名单是**必须**的
 *
 * 写入口对页面脚本（以及页面里的 XSS）开放，所以：
 * - 只认识 [KEYS] 里的键，其余一律拒绝；
 * - 值再校验一次（布尔只认 `1` / `0` / `true` / `false`，样式只认三种，语言必须是已知语言码）；
 * - **凭据类字段（API Key / 模型 / 接入地址 / 服务商）不在白名单里**，页面永远改不到它们，
 *   这与「Key 绝不注入网页」是同一条边界（见 [TranslateSettings.pageConfigJson]）。
 *
 * [apply] 是纯函数（不碰 Android API），单测直接拿它钉住白名单与取值。
 */
object TranslateQuickSettings {

    /** 允许页面写入的键。 */
    val KEYS: Set<String> = setOf("enabled", "dual", "style", "target", "persist", "protect")

    /**
     * 把一次页面写入折算成新配置。
     *
     * @return 新配置；键不认识、值不合法时返回 null（调用方什么都不做）
     */
    fun apply(config: TranslateConfig, key: String, value: String): TranslateConfig? = when (key) {
        "enabled" -> boolOf(value)?.let { config.copy(enabled = it) }
        "dual" -> boolOf(value)?.let { config.copy(dual = it) }
        "persist" -> boolOf(value)?.let { config.copy(persist = it) }
        "protect" -> boolOf(value)?.let { config.copy(protect = it) }
        "style" -> value.takeIf { it in TranslateConfig.STYLES }?.let { config.copy(style = it) }
        "target" -> TranslateLang.entries.firstOrNull { it.code == value }?.let { config.copy(target = it.code) }
        else -> null
    }

    /**
     * 落盘。返回 false = 没被接受（键不在白名单 / 值非法），页面侧不必重试。
     *
     * 只写被改的那一个键：`reset()` 之外没有「整份覆盖」的入口，避免页面顺手把
     * 别的设置也刷掉。
     */
    fun persist(context: Context, key: String, value: String): Boolean {
        val next = apply(TranslateSettings.read(context), key, value) ?: return false
        when (key) {
            "enabled" -> TranslateSettings.setEnabled(context, next.enabled)
            "dual" -> TranslateSettings.setDual(context, next.dual)
            "persist" -> TranslateSettings.setPersist(context, next.persist)
            "protect" -> TranslateSettings.setProtect(context, next.protect)
            "style" -> TranslateSettings.setStyle(context, next.style)
            "target" -> TranslateSettings.setTarget(context, next.target)
        }
        return true
    }

    /** 页面侧传的是 `1` / `0`（与注入配置里的布尔写法保持一致），这里再兜住字面量。 */
    private fun boolOf(value: String): Boolean? = when (value) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }
}
