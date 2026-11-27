package com.branchbase.ui.search

import androidx.annotation.StringRes
import com.branchbase.core.engineErrorStatus
import com.branchbase.R
import com.branchbase.ui.LocalizedText

/**
 * 搜索链路的纯逻辑（查询组装 / 缓存键 / 错误分级）。
 *
 * 单独成文件是为了可单测：这三件事都是「改一行就可能悄悄坏掉」的地方 ——
 * 查询串少了语言限定就搜出别的语言、缓存键少了账号就会串私有结果、
 * 限流被当成网络错误用户就不知道该等还是该改搜索词。
 */

/**
 * 组装 GitHub 搜索查询串。
 *
 * 组成：用户输入 + `language:` + 高级筛选（`stars:` / `topic:` …）+ 类型限定（`type:issue` / `type:pr`）。
 *
 * 注意**不要**在这里拼 `page` / `per_page`：它们是 URL 参数而不是查询词，
 * 拼进来会被当成搜索关键字（GitHub 的 search 语法里没有 page 限定符）。
 */
internal fun buildSearchQuery(
    query: String,
    type: SearchType,
    language: String?,
    advanced: Map<String, String>,
    advancedFilters: List<Pair<String, String>>,
): String = buildString {
    append(query.trim())
    language?.takeIf { it.isNotBlank() }?.let { append(" language:$it") }
    advanced.forEach { (name, value) ->
        val syntax = advancedFilters.firstOrNull { it.first == name }?.second ?: return@forEach
        if (value.isNotBlank()) append(" $syntax$value")
    }
    // 类型限定符来自枚举，不再判别中文字面量：翻译搜索页的 Tab 文案不会改变查询串
    type.queryQualifier?.let { append(" $it") }
}

/**
 * 搜索缓存键。
 *
 * ## 键里放的是「生效查询串」，不是用户敲的原文
 *
 * 语言与高级筛选会被 [buildSearchQuery] 拼成 `language:` / `stars:` 这类限定符。1.4.92 及更早
 * 只用**原文**做键：改语言、改高级筛选算出来的键一模一样 —— 抽屉里拨完一整套条件点「完成」，
 * `SearchScreen` 确实重新发起了搜索，但 `SearchCacheManager.get` 命中同一条旧行、直出旧结果，
 * 用户读到的就是「根本没重搜」。现在把四维条件一起喂进键：任有一维变化就换一条键。
 *
 * ## 必须带账号（这是修掉的一个真实缺陷）
 *
 * 搜索结果**受当前账号权限影响**：私有仓库、私有代码、仅自己可见的 issue 都会出现在结果里。
 * 原来的键只有「类型|查询|排序」，于是换账号后可能命中上一个账号的结果 ——
 * 既是错误结果，也是权限泄漏（A 的私有仓库出现在 B 的搜索里）。
 * 同项目里 `SecurityAlertScreen` 早就为此用了 `PageCache.profileKey`，搜索这里漏了。
 *
 * 只有「仓库」类型带排序键：其余类型不支持排序，带上会让 `sortKey` 残留污染缓存键。
 *
 * 键里用 [SearchType.cacheKey]（稳定英文）而不是展示文案 —— 缓存是**落库**的，
 * 切一次界面语言不该让整张缓存表变成读不到的孤儿。
 */
internal fun searchCacheKey(
    type: SearchType,
    query: String,
    language: String?,
    advanced: Map<String, String>,
    advancedFilters: List<Pair<String, String>>,
    sortKey: String,
    login: String,
): String {
    val effective = buildSearchQuery(query, type, language, advanced, advancedFilters)
    return if (type == SearchType.REPOS) {
        "search:$login:${type.cacheKey}|$effective|$sortKey"
    } else {
        "search:$login:${type.cacheKey}|$effective"
    }
}

/**
 * 把后端错误翻成**用户能据此行动**的一句话。
 *
 * 后端（Rust `api::client`）的失败文本形如 `ERROR:HTTP 403 Forbidden: {"message":"API rate limit exceeded…"}`，
 * 状态码与 GitHub 的 message 都在里面，所以这里能区分限流 / 语法错误 / 未登录 / 网络问题。
 * 原来一律回「搜索失败，请稍后重试（可能触发速率限制）」——用户既不知道等多久，也不知道是不是自己搜错了。
 */
internal fun friendlySearchError(raw: String?): LocalizedText {
    val msg = raw.orEmpty()
    // 状态码走唯一一处解析（`engineErrorStatus` 同时认新码与旧文案），不在本函数里再写一份
    // 「contains("403")」—— 那既会把正文里的数字当成状态码，也会在引擎换码后静默失效。
    val status = engineErrorStatus(msg)
    return when {
        status == 429 || status == 403 ||
            msg.contains("rate limit", ignoreCase = true) ||
            msg.contains("secondary rate", ignoreCase = true) ->
            LocalizedText(R.string.search_error_rate_limited)

        // 422 里有一类是「翻到底了」：GitHub 只提供前 1000 条搜索结果，
        // 再往下翻会返回这条 message。把它当成语法错误会让用户白改筛选。
        searchResultCapReached(msg) -> LocalizedText(R.string.search_error_cap_reached)

        status == 422 ||
            msg.contains("Validation Failed", ignoreCase = true) ||
            msg.contains("validation failed", ignoreCase = true) ->
            LocalizedText(R.string.search_error_validation)

        status == 401 || msg.contains("Bad credentials", ignoreCase = true) ->
            LocalizedText(R.string.search_error_unauthorized)

        msg.isBlank() -> LocalizedText(R.string.search_error_no_response)

        else -> LocalizedText(R.string.search_error_raw, listOf(msg.take(120)))
    }
}

/**
 * 是否是「已到 GitHub 搜索结果上限」的报错。
 *
 * GitHub 的 search 接口**只返回前 1000 条**，`page` 超过上限时给 422 +
 * `Only the first 1000 search results are available`。这是「翻到头了」，不是用户搜错了，
 * 所以单独判出来：既换一句能行动的提示，也让底部「加载更多」按钮收掉（否则用户会一直点、一直报错）。
 */
internal fun searchResultCapReached(raw: String?): Boolean {
    val msg = raw.orEmpty()
    return msg.contains("first 1000 search results", ignoreCase = true) ||
        msg.contains("1000 search results", ignoreCase = true)
}

/**
 * 结果计数文案。
 *
 * 搜索结果目前只取第一页（GitHub 默认 30 条/页），而 `total_count` 是总量 ——
 * 直接写「共 1200 个结果」会让人以为能翻到 1200 条。这里把「已显示多少 / 共多少」说清楚，
 * 避免用户反复下拉却"加载不出来"。
 */
internal fun resultCountText(total: Long, shown: Int, @StringRes unitRes: Int): LocalizedText =
    if (total <= shown) {
        LocalizedText(R.string.result_count_all, listOf(total, LocalizedText(unitRes)))
    } else {
        LocalizedText(
            R.string.result_count_partial,
            listOf(shown, total, LocalizedText(unitRes)),
        )
    }

/**
 * 分页状态。
 *
 * @param page 已经拿到的页码（1 = 只拿了第一页）
 * @param total 服务端返回的总量（`total_count`）
 * @param shown 当前列表里已展示的条数
 */
internal data class PagingState(val page: Int, val total: Long, val shown: Int) {
    /** 还有没有下一页：已展示数没到总量，且这一页不是空的（服务端 total 可能虚高）。 */
    val hasMore: Boolean get() = shown > 0 && shown < total
}

/**
 * 合并新一页结果（**按 key 去重**）。
 *
 * 为什么要去重：GitHub 的分页在数据变动时会出现**同一项跨页重复**（尤其按 stars/updated 排序时），
 * 不去重会让列表里冒出重复卡片。去重保留先出现的那份（顺序即服务端顺序）。
 */
internal fun <T> mergePage(existing: List<T>, incoming: List<T>, key: (T) -> String): List<T> {
    if (incoming.isEmpty()) return existing
    val seen = existing.mapTo(HashSet()) { key(it) }
    val merged = existing.toMutableList()
    incoming.forEach { item ->
        val k = key(item)
        if (seen.add(k)) merged += item
    }
    return merged
}

/**
 * 计算下一页的页码。
 *
 * 用「**本页实际拿到多少条**」而不是固定 30 推断：GitHub 的 `per_page` 默认 30，
 * 但最后一页可能不足；若按固定步长推进，遇到服务端限流返回空页就会永远卡在同一页。
 */
internal fun nextPage(currentPage: Int, received: Int): Int = if (received <= 0) currentPage else currentPage + 1

/** 底部「加载更多」的文案（既说清进度，也避免用户以为能一直下拉）。 */
internal fun loadMoreText(shown: Int, total: Long): LocalizedText =
    LocalizedText(R.string.search_load_more_progress, listOf(shown, total))
