package com.branchbase.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * 一段**待解析**的文案：资源 ID + 参数。
 *
 * ## 为什么需要它
 *
 * 模型层（数据类、枚举、JSON 解析结果）不认识 Context，但它们的字段要显示给用户。
 * 直接存中文 `String` 会让界面语言切换后不更新；给这些函数加 `Context` 参数又会让
 * 纯逻辑不可单测。所以模型只带「资源 ID + 参数」，**渲染时**才解析：
 *
 * ```kotlin
 * // 产出侧（模型 / 解析函数）
 * LocalizedText(R.string.timeline_renamed, listOf(actor, from, to))
 * LocalizedText(raw = type.removeSuffix("Event"))     // 没有对应资源时原样透出
 *
 * // 渲染侧（Composable）
 * Text(entry.text.resolve(context))
 * ```
 *
 * ## 三个能力
 *
 * 1. **参数可以嵌套 [LocalizedText]**：`"${eventAction(action)}拉取请求 #$n"` 这种
 *    「把一个 helper 的结果拼进句子」在模型里很常见，嵌套后仍然只解析一次、
 *    且顺序由资源的 `%1$s` 决定（英文语序才拼得对）。
 * 2. **[raw] 原样透出**：后端新增的事件类型没有对应资源，不能被吞掉 ——
 *    这与 `typeShortNameResOrNull` 返回 `null` 是同一个约定，只是这里还要把原文带出去。
 * 3. **[quantity] 表达复数**：中文只有 `other`，英文要分 `one` / `other`
 *    （`1 minute ago` / `2 minutes ago`），只有 `<plurals>` 能表达，走 [plural] 构造。
 *
 * 注意 [raw] 与 [res] 是**二选一**：`res == 0` 时才用 `raw`。
 */
data class LocalizedText(
    /** 资源 ID；为 0 表示「没有对应资源」，此时用 [raw]。 */
    @param:StringRes val res: Int = 0,
    val args: List<Any> = emptyList(),
    /** 没有资源时的原文（后端新增的类型原样透出，不吞掉）。 */
    val raw: String = "",
    /**
     * 非空表示 [res] 指的是 `<plurals>` 资源，按这个数量选形。
     *
     * ⚠️ **不要直接传这个参数，用 [plural]。** [res] 的类型是 `Int`，编译器与
     * lint 都分不出它装的是 `<string>` 还是 `<plurals>`；直接构造的话，
     * 传错要到运行时才炸 —— `getQuantityString` 拿到 `<string>` 会抛
     * `Resources$NotFoundException`。工厂入口把「哪种资源」约束在签名上。
     */
    val quantity: Int? = null,
) {
    /** 按当前界面语言解析。嵌套的 [LocalizedText] 参数会先被解析。 */
    fun resolve(context: Context): String {
        if (res == 0) return raw
        val flat = args.map { if (it is LocalizedText) it.resolve(context) else it }
        val formatArgs = flat.toTypedArray()
        return if (quantity != null) {
            context.resources.getQuantityString(res, quantity, *formatArgs)
        } else {
            context.getString(res, *formatArgs)
        }
    }

    companion object {
        /**
         * 构造一条 `<plurals>` 文案：`LocalizedText.plural(R.plurals.relative_minutes, 5, listOf(5))`。
         *
         * `@PluralsRes` 是这里唯一的把关点 —— 传 `<string>` 的资源 ID 会被 lint 拦下，
         * 而不是等到运行时抛 `Resources$NotFoundException`。
         * （[res] 字段上的 `@StringRes` 与 `@PluralsRes` 不同源，故在本函数上抑制。）
         */
        @Suppress("ResourceType")
        fun plural(@PluralsRes res: Int, quantity: Int, args: List<Any> = emptyList()): LocalizedText =
            LocalizedText(res = res, args = args, quantity = quantity)
    }
}
