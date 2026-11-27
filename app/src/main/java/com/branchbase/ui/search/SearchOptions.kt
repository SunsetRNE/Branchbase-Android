package com.branchbase.ui.search

import android.content.Context
import org.json.JSONObject

/**
 * 搜索选项的**记忆**：目标类型 / 排序倾向 / 语言 / 高级筛选，四维一套。
 *
 * ## 为什么要落盘
 *
 * 这四项原先只活在本页 `remember`（后来进了 [SearchViewModel]），返回、旋转都还在，
 * 但**进程被杀就归零**。而搜索页的价值恰恰是「同一套矩阵反复用」：
 * 同一句关键词换语言、换排序、换仓库/代码，每次都从默认值重新拨一遍抽屉，
 * 等于每搜一次都要重走四步。
 *
 * 落盘之后：进页面时抽屉里就是上次的矩阵，搜索框的徽标也直接接着上次的形状。
 *
 * ## 为什么按账号分开存
 *
 * 与 [searchCacheKey] 同一个理由：结果受账号权限影响，私有仓库 / 私有代码的检索条件
 * 不该跟着账号串。键尾带 login，换账号就是另一套记忆；未登录时落在 `anonymous` 下。
 *
 * 存的是 `SearchType.cacheKey`（稳定英文），不是展示名 —— 切界面语言不会让记忆失效。
 */
internal data class SearchOptions(
    val type: SearchType = SearchType.REPOS,
    val sortKey: String = "",
    val language: String? = null,
    val advanced: Map<String, String> = emptyMap(),
) {
    /** 已生效的高级筛选条数（空值不算：抽屉里展开过又没填的行不产生徽标）。 */
    val advancedCount: Int get() = advanced.count { it.value.isNotBlank() }

    /** 除「目标」外是否还有非默认选项（决定入口卡是否点亮）。 */
    val hasSecondary: Boolean get() = sortKey.isNotBlank() || language != null || advancedCount > 0
}

/**
 * [SearchOptions] 的落盘读写（SharedPreferences + JSON）。
 *
 * 单键存整份 JSON 而不是四个平铺的键：四个键各存各的时，「读了一半、写了一半」会拼出
 * 一个不存在的组合（例如 type 换了、advanced 还是上一套）；整份读写才是原子的。
 */
internal class SearchOptionStore(context: Context, login: String) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val key = "options:${login.ifBlank { ANONYMOUS }}"

    fun load(): SearchOptions? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val advancedObj = obj.optJSONObject("advanced")
            val advanced = buildMap {
                advancedObj?.keys()?.forEach { name ->
                    val value = advancedObj.optString(name)
                    if (value.isNotBlank()) put(name, value)
                }
            }
            SearchOptions(
                // 未知类型（旧版本存的值 / 改名之后）退回默认，而不是让整份记忆失效
                type = SearchType.entries.firstOrNull { it.cacheKey == obj.optString("type") }
                    ?: SearchType.REPOS,
                sortKey = obj.optString("sort"),
                language = obj.optString("language").takeIf { it.isNotBlank() },
                advanced = advanced,
            )
        }.getOrNull()
    }

    fun save(options: SearchOptions) {
        val obj = JSONObject().apply {
            put("type", options.type.cacheKey)
            put("sort", options.sortKey)
            put("language", options.language ?: "")
            put("advanced", JSONObject(options.advanced))
        }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    private companion object {
        const val PREFS = "branchbase_search_options"
        const val ANONYMOUS = "anonymous"
    }
}
