package com.branchbase.ui.search

import androidx.annotation.StringRes
import com.branchbase.R

/**
 * 搜索类型（代码 / 仓库 / 议题 / 拉取请求 / 用户 / 提交 / 主题）。
 *
 * ## 为什么要有这个枚举
 *
 * 改前它是 7 个中文字符串字面量（`SearchScreen` 里的 `types = listOf("代码", …)`），
 * 一个字符串同时充当**三件事**：
 *
 * 1. Tab 上的展示文案；
 * 2. 十几处 `when (type)` 的判别值（解析分支、请求分支、计数分支）；
 * 3. 缓存键的成分（`search:$login:$type|$query`）与 TTL 派发的入参。
 *
 * 三者混在一起时，「把界面文案抽成资源并翻译」这一步会**静默破坏后两者**：
 * 界面切成英文后 `when (type)` 匹配不上、缓存键跟着语言变、`ttlFor` 落到 `else` 分支。
 * 拆开之后：**展示走 [labelRes]、判别走枚举本身、持久化走 [cacheKey]**，
 * 三者互不影响，翻译界面文案不再有副作用。
 *
 * ## cacheKey 为什么是英文而不是 `name`
 *
 * 用 `name`（`CODE` / `REPOS`）也能跑，但缓存键是**写进数据库**的：
 * 改名（比如把 `REPOS` 改成 `REPOSITORIES`）等于让所有旧行变成孤儿。
 * 单独给一个显式的小写 `cacheKey` 并写明「改了要一起迁移」，比隐式依赖枚举名安全。
 *
 * ## 顺序即 Tab 顺序
 *
 * 枚举声明顺序就是搜索页类型菜单的显示顺序（[entries] 直接拿来渲染），
 * 调整顺序会改变用户看到的排列 —— 这是刻意的：顺序是一处定义，不是两处。
 */
enum class SearchType(
    /** Tab 上的展示名（资源，随界面语言变）。 */
    @StringRes val labelRes: Int,
    /** 稳定内部键：进缓存键、进 TTL 派发。**随界面语言变的东西一律不许进这里。** */
    val cacheKey: String,
    /** GitHub 搜索语法限定符（`type:issue` / `type:pr`）；不需要限定的类型为 null。 */
    val queryQualifier: String? = null,
) {
    CODE(R.string.search_type_code, "code"),
    REPOS(R.string.search_type_repos, "repos"),
    ISSUES(R.string.search_type_issues, "issues", queryQualifier = "type:issue"),
    PULLS(R.string.search_type_pulls, "pulls", queryQualifier = "type:pr"),
    USERS(R.string.search_type_users, "users"),
    COMMITS(R.string.search_type_commits, "commits"),
    TOPICS(R.string.search_type_topics, "topics"),
}
