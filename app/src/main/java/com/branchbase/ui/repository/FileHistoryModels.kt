package com.branchbase.ui.repository

import org.json.JSONArray

/**
 * 「文件历史」档（[GitPanelKind.FileHistory]）的视图模型与解析 —— 纯逻辑，可 JVM 单测。
 *
 * 真源：[`git-mode-design.md`](../../../../../../docs/specs/git-mode-design.md) §3.2（视图档表）
 * 与 D-e（**本地优先，REST 兜底；未加深时给「加深克隆」入口**）。
 *
 * ## 两条来源的字段口径不一样，所以有两个解析器
 *
 * | 来源 | 形状 | 解析 |
 * |---|---|---|
 * | 本地 `log_file` | **扁平** `[{sha, subject, author, date}]`（新的在前） | [parseLocalFileHistory] |
 * | REST `/commits?path=` | **嵌套** `[{sha, commit:{message, author:{name,date}}}]` | [parseRestFileHistory] |
 *
 * 与提交图那两个解析器（`parseGraphCommits` / `parseLocalGraphCommits`）同一条口径：
 * 字段口径不同就**各解析各的**，不写一个「两边都能吃」的宽容解析器 —— 那种写法在下一次
 * 改接口时不会报错，只会静默少字段。
 */

/** 文件历史里的一条提交。 */
internal data class FileHistoryCommit(
    val fullSha: String,
    val subject: String,
    val author: String,
    val date: String,
) {
    val shortSha: String get() = fullSha.take(7)
}

/** 这一档的数据来源。 */
internal enum class FileHistorySource { LOCAL, REST }

/**
 * 择源：本地仓库存在**且不是浅克隆** → 本地 `log_file`；否则 REST `/commits?path=` 兜底。
 *
 * 与提交图（[graphSourceOf]）同一条判据、同一个理由：`log_file` 是 revwalk 逐个提交比对树，
 * 浅克隆里本地只有 HEAD 一条 —— 走本地会得到「这个文件没有历史」这种**假话**
 * （文件明明有历史，只是不在本地）。浅克隆的出路同样是「加深」（D-e 明确要求给这个入口）。
 */
internal fun fileHistorySourceOf(localRepoExists: Boolean, localShallow: Boolean): FileHistorySource =
    if (localRepoExists && !localShallow) FileHistorySource.LOCAL else FileHistorySource.REST

/**
 * 列表里的提交能不能点开看 diff。
 *
 * **只有本地来源能**：`diff_commit` 读的是本地对象库，浅克隆 / 没有本地仓库时那些提交
 * 本地根本没有对象 —— 点了只会得到一句「读取失败」。与其给一个注定失败的入口，
 * 不如让行不可点（本仓库的既有口径：不放假入口）。
 */
internal fun canOpenCommitDiff(source: FileHistorySource): Boolean = source == FileHistorySource.LOCAL

/** 一页取数请求（与提交图同形：本地按 `skip`、REST 按最老 sha）。 */
internal sealed interface FileHistoryPage {
    data class Local(val skip: Int) : FileHistoryPage
    data class Rest(val sha: String?) : FileHistoryPage
}

/** 下一页取数键：本地给 `skip = 已加载条数`，REST 给最老那条 sha（首页为 null）。 */
internal fun nextFileHistoryPage(source: FileHistorySource, loaded: List<FileHistoryCommit>): FileHistoryPage =
    when (source) {
        FileHistorySource.LOCAL -> FileHistoryPage.Local(skip = loaded.size)
        FileHistorySource.REST -> FileHistoryPage.Rest(sha = loaded.lastOrNull()?.fullSha)
    }

/**
 * 解析本地 `log_file` 的输出（扁平 JSON，新的在前）。
 *
 * 实测取证（`core/src/git/mod.rs` 真跑出来的形状）：
 * `[{"author":"Dave","date":"2026-09-25T09:02:48+00:00","sha":"a43c73…","subject":"第三次改 a"}]`；
 * 没碰过该路径的文件给 `[]`（**不是错误**）—— 界面按「这个文件还没有提交历史」渲染。
 */
internal fun parseLocalFileHistory(json: String?): List<FileHistoryCommit> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val sha = o.optString("sha")
            if (sha.isBlank()) return@mapNotNull null
            FileHistoryCommit(
                fullSha = sha,
                subject = o.optString("subject"),
                author = o.optString("author"),
                date = o.optString("date"),
            )
        }
    }.getOrDefault(emptyList())
}

/** 解析 REST `/commits?path=` 的输出（嵌套 JSON，新的在前）。 */
internal fun parseRestFileHistory(json: String?): List<FileHistoryCommit> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val sha = o.optString("sha")
            if (sha.isBlank()) return@mapNotNull null
            val commit = o.optJSONObject("commit")
            FileHistoryCommit(
                fullSha = sha,
                subject = commit?.optString("message")?.lineSequence()?.firstOrNull().orEmpty(),
                author = commit?.optJSONObject("author")?.optString("name")
                    ?: o.optJSONObject("author")?.optString("login").orEmpty(),
                date = commit?.optJSONObject("author")?.optString("date").orEmpty(),
            )
        }
    }.getOrDefault(emptyList())
}
