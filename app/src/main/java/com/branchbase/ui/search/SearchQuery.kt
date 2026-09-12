package com.branchbase.ui.search

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
    type: String,
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
    when (type) {
        "议题" -> append(" type:issue")
        "拉取请求" -> append(" type:pr")
    }
}

/**
 * 搜索缓存键。
 *
 * ## 必须带账号（这是修掉的一个真实缺陷）
 *
 * 搜索结果**受当前账号权限影响**：私有仓库、私有代码、仅自己可见的 issue 都会出现在结果里。
 * 原来的键只有「类型|查询|排序」，于是换账号后可能命中上一个账号的结果 ——
 * 既是错误结果，也是权限泄漏（A 的私有仓库出现在 B 的搜索里）。
 * 同项目里 `SecurityAlertScreen` 早就为此用了 `PageCache.profileKey`，搜索这里漏了。
 *
 * 只有「仓库」类型带排序键：其余类型不支持排序，带上会让 `sortKey` 残留污染缓存键。
 */
internal fun searchCacheKey(type: String, query: String, sortKey: String, login: String): String =
    if (type == "仓库") "search:$login:$type|$query|$sortKey" else "search:$login:$type|$query"

/**
 * 把后端错误翻成**用户能据此行动**的一句话。
 *
 * 后端（Rust `api::client`）的失败文本形如 `ERROR:HTTP 403 Forbidden: {"message":"API rate limit exceeded…"}`，
 * 状态码与 GitHub 的 message 都在里面，所以这里能区分限流 / 语法错误 / 未登录 / 网络问题。
 * 原来一律回「搜索失败，请稍后重试（可能触发速率限制）」——用户既不知道等多久，也不知道是不是自己搜错了。
 */
internal fun friendlySearchError(raw: String?): String {
    val msg = raw.orEmpty()
    return when {
        msg.contains("rate limit", ignoreCase = true) ||
            msg.contains("secondary rate", ignoreCase = true) ||
            msg.contains("429") ||
            msg.contains("403") ->
            "被 GitHub 限流了。搜索类接口限制很严（代码搜索约 10 次/分钟、其它约 30 次/分钟），等 1 分钟再试。"

        // 422 里有一类是「翻到底了」：GitHub 只提供前 1000 条搜索结果，
        // 再往下翻会返回这条 message。把它当成语法错误会让用户白改筛选。
        searchResultCapReached(msg) ->
            "已到 GitHub 的搜索上限（只提供前 1000 条结果）。用语言 / 星标数 / 组织等条件缩小范围，比继续翻页有效。"

        msg.contains("422") || msg.contains("Validation Failed", ignoreCase = true) ||
            msg.contains("validation failed", ignoreCase = true) ->
            "搜索条件不被接受（语法或筛选值有误）。可清掉部分高级筛选后重试。"

        msg.contains("401") || msg.contains("Bad credentials", ignoreCase = true) ->
            "登录状态已失效，请重新登录。"

        msg.isBlank() -> "搜索失败（无响应）。检查网络后重试。"

        else -> "搜索失败：${msg.take(120)}"
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
internal fun resultCountText(total: Long, shown: Int, unit: String): String =
    if (total <= shown) "$total 个$unit" else "已显示前 $shown 条 · 共 $total 条$unit"

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
internal fun loadMoreText(shown: Int, total: Long): String =
    "加载更多（已显示 $shown / 共 $total）"
