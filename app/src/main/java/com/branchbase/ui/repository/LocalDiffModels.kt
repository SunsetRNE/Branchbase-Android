package com.branchbase.ui.repository

import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地 diff（`diff_worktree` / `diff_commit`）的视图模型与解析 —— 纯逻辑，可 JVM 单测。
 *
 * 真源：`docs/specs/git-mode-design.md` §3.2（改动清单点开看 diff）/ §11.6（落点选型）。
 *
 * ## 为什么要把一整份 patch 拆成一段一段
 *
 * 引擎给的是**一次 diff 的整体 patch**（多个文件连在一起，每段以 `diff --git ` 开头），
 * 而 [parseUnifiedDiff]（`BranchDiff.kt`）是**按单文件 patch** 写的：它的元信息行规则里
 * `--- ` / `+++ ` 只在**首个 hunk 之前**跳过 —— 第二个文件的 `--- a/y` 会被当成
 * 「删掉一行 `-- a/y`」。`BranchCompareScreen` 之所以没踩到，是因为它拿的是 GitHub 逐文件的
 * patch；本地这份必须在这里先拆开。
 *
 * ## 两份数据按下标对齐（不解析路径）
 *
 * `files[i]` 与 patch 里的第 i 段来自**同一次 diff 的第 i 个 delta**（引擎侧同序遍历生成，
 * `core/src/git/mod.rs` 的 `render_diff`）。按路径去配看起来很直观，但路径里有空格 / 中文时
 * 解析 `diff --git a/… b/…` 的 header 很脆（git 会加引号转义），而**配错的代价是把 A 文件的
 * 差异画在 B 名下** —— 宁可少画，不可画错。所以这里按下标对齐；两段数量对不上时
 * （理论上不会发生，引擎有 `diff_worktree_未跟踪文件带内容且两段按下标对齐` 钉着）
 * 多出来的文件按「没有可显示的差异」处理，而不是错位取用。
 */

/** 一档本地 diff 的数据。 */
internal data class LocalDiffView(
    val files: List<LocalDiffFile>,
    /** patch 超上限被引擎截断（**必须如实显示**，否则会让人以为「改动就这么点」）。 */
    val truncated: Boolean,
) {
    val isEmpty: Boolean get() = files.isEmpty()
}

/** 一个文件在这次 diff 里的样子。 */
internal data class LocalDiffFile(
    val path: String,
    /** `A` 新增 / `M` 修改 / `D` 删除 / `R` 重命名（引擎的字母口径）。 */
    val status: String,
    val additions: Int,
    val deletions: Int,
    /** 该文件的那一段 patch（原始文本）。 */
    val patch: String,
) {
    /**
     * 这一段有没有**可渲染的 hunk**。
     *
     * 二进制文件在 patch 里只有一句 `Binary files … differ`（没有 `@@`）——
     * 直接丢给 `parseUnifiedDiff` 会被当成一行上下文代码，界面上就会出现一行假的行号 +
     * 一句英文提示，比不画更坏。所以「有没有 hunk」是能不能渲染的判据，不是「patch 是否为空」。
     */
    val hasHunks: Boolean get() = patchHasHunks(patch)
}

/** patch 里有没有 hunk 头（`@@ -a,b +c,d @@`）。引擎渲染时 hunk 头行**不带**行首前缀。 */
internal fun patchHasHunks(patch: String): Boolean =
    patch.lineSequence().any { it.startsWith("@@ ") }

/**
 * 把整体 patch 按 `diff --git ` 边界拆成若干段（**段内保留原文**，不做任何加工）。
 *
 * 返回的是**裸段**：不解析路径、不做配对 —— 配对由 [parseLocalDiff] 按下标完成。
 */
internal fun splitPatchByFile(patch: String): List<String> {
    if (patch.isBlank()) return emptyList()
    val out = mutableListOf<StringBuilder>()
    for (line in patch.lineSequence()) {
        if (line.startsWith("diff --git ")) out += StringBuilder()
        // 第一段之前的内容（理论上没有）丢掉：它不是任何一个文件的差异
        out.lastOrNull()?.append(line)?.append('\n')
    }
    return out.map { it.toString() }
}

/**
 * 解析引擎的 `{ patch, files, truncated }`。
 *
 * **解析不出来返回 `null`**（调用方显示失败态）—— 与 [parseLocalGraphCommits] 的
 * 「空数组是有意义的另一件事」同一个口径：`null` 是「读不到」，`files` 为空才是「没有改动」。
 */
internal fun parseLocalDiff(json: String?): LocalDiffView? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val root = JSONObject(json)
        val sections = splitPatchByFile(root.optString("patch"))
        val stats: JSONArray = root.optJSONArray("files") ?: JSONArray()
        // 两段数量对不上时**不按下标配**（宁可全都不画，也不把 A 的差异画到 B 名下）。
        // 引擎那边有 `diff_worktree_未跟踪文件带内容且两段按下标对齐` 钉着这条不变量，
        // 所以这只是「拿着旧 .so 跑新界面」时的兜底
        val aligned = sections.size == stats.length()
        val files = (0 until stats.length()).mapNotNull { i ->
            val o = stats.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("path")
            if (path.isBlank()) return@mapNotNull null
            LocalDiffFile(
                path = path,
                status = o.optString("status").ifBlank { "M" }.take(1),
                additions = o.optInt("additions"),
                deletions = o.optInt("deletions"),
                patch = if (aligned) sections.getOrNull(i).orEmpty() else "",
            )
        }
        LocalDiffView(files = files, truncated = root.optBoolean("truncated", false))
    }.getOrNull()
}

/**
 * 这一档要画的**行**（文件头 + 差异行拉平成一列，交给一个 `LazyColumn`）。
 *
 * 为什么拉平而不是「每个文件一个 `Column`」：一个文件的差异可能有几千行，
 * 嵌套在 `LazyColumn` 的 item 里会一次性组合完（`LazyColumn` 只懒加载它的**直接** item），
 * 一屏百行时这正是「能滑动」与「滑动掉帧」的分界 —— 与提交图那条同一个道理。
 */
internal sealed interface LocalDiffRow {
    data class Header(val file: LocalDiffFile) : LocalDiffRow
    /** 差异行；[filePath] 是它的归属（渲染 key 要用 —— 不同文件的 hunk 头可能逐字相同）。 */
    data class Line(val filePath: String, val line: DiffLine) : LocalDiffRow
    /** 该文件没有可显示的文本差异（二进制 / 只有模式或重命名变化）。 */
    data class NoPatch(val file: LocalDiffFile) : LocalDiffRow
}

/**
 * 把一档 diff 摊成可懒加载的行序列。
 *
 * `onlyPath` 非空时**只留那一个文件**（从改动清单点进来的场景：用户点的是某一行，
 * 打开的页面就该是那个文件，而不是一份要他再找一遍的全量差异）。
 */
internal fun localDiffRows(view: LocalDiffView, onlyPath: String? = null): List<LocalDiffRow> {
    val files = if (onlyPath.isNullOrBlank()) view.files else view.files.filter { it.path == onlyPath }
    val rows = mutableListOf<LocalDiffRow>()
    files.forEach { file ->
        rows += LocalDiffRow.Header(file)
        if (file.hasHunks) {
            parseUnifiedDiff(file.patch).forEach { rows += LocalDiffRow.Line(file.path, it) }
        } else {
            rows += LocalDiffRow.NoPatch(file)
        }
    }
    return rows
}
