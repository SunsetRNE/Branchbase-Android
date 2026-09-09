package com.branchbase.ui.repository

import org.json.JSONArray
import org.json.JSONObject

/**
 * 分支对比（`GET /repos/{o}/{r}/compare/{base}...{head}`）的数据模型与解析。
 *
 * 对齐 `docs/repository-detail-wireframe.md` §「文件对比（Compare branches）」：
 * 对比头（`比较 base ← head` + 领先/落后）→ 文件列表（文件名 + `+N -M`）→ 文件内代码片段（unified diff）。
 *
 * 注意 GitHub 的边界：单次最多返回 300 个文件，且**超大 diff 不给 `patch` 字段**
 * （此时 `patch` 为空串，界面需明确提示而不是显示空白）。
 */
data class CompareResult(
    val status: String,
    val aheadBy: Int,
    val behindBy: Int,
    val totalCommits: Int,
    val commits: List<CompareCommit>,
    val files: List<CompareFile>,
) {
    /** 是否有任何差异（identical = 两分支内容相同）。 */
    val identical: Boolean get() = status == "identical" || (aheadBy == 0 && behindBy == 0 && files.isEmpty())

    /** 分叉：双方各有独有提交 —— 只能合并/覆盖，不能快进。 */
    val diverged: Boolean get() = aheadBy > 0 && behindBy > 0
}

/** 对比中的一条提交（只保留列表展示需要的字段）。 */
data class CompareCommit(
    val sha: String,
    val message: String,
    val author: String,
    val date: String,
) {
    val shortSha: String get() = sha.take(7)
    val subject: String get() = message.lineSequence().firstOrNull().orEmpty()
}

/** 对比中的单个文件差异。 */
data class CompareFile(
    val filename: String,
    val previousFilename: String?,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val patch: String,
    val blobUrl: String,
) {
    /** 文件级增删摘要（`+12 -3`）。 */
    val changeSummary: String get() = "+$additions -$deletions"

    /** 状态中文标签（GitHub 的 status 取值：added/removed/modified/renamed/changed）。 */
    val statusLabel: String
        get() = when (status) {
            "added" -> "新增"
            "removed" -> "删除"
            "renamed" -> "重命名"
            "modified" -> "修改"
            "changed" -> "变更"
            else -> status
        }

    /** GitHub 对大 diff 不返回 patch：界面要提示「无代码片段」而不是留白。 */
    val hasPatch: Boolean get() = patch.isNotBlank()

    /** 目录部分（`docs/a.md` → `docs/`；根目录 → 空串）。 */
    val dir: String get() = filename.substringBeforeLast('/', "")

    /** 文件名部分（不含目录）。 */
    val name: String get() = filename.substringAfterLast('/')
}

/** 解析 compare API 的原始 JSON；结构不符时返回 null（不抛异常）。 */
fun parseCompareResult(json: String?): CompareResult? {
    if (json.isNullOrBlank() || json.startsWith("ERROR:")) return null
    return runCatching {
        val o = JSONObject(json)
        CompareResult(
            status = o.optString("status"),
            aheadBy = o.optInt("ahead_by", 0),
            behindBy = o.optInt("behind_by", 0),
            totalCommits = o.optInt("total_commits", 0),
            commits = parseCompareCommits(o.optJSONArray("commits")),
            files = parseCompareFiles(o.optJSONArray("files")),
        )
    }.getOrNull()
}

private fun parseCompareCommits(arr: JSONArray?): List<CompareCommit> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val c = arr.optJSONObject(i) ?: return@mapNotNull null
        val sha = c.optString("sha")
        if (sha.isBlank()) return@mapNotNull null
        val commit = c.optJSONObject("commit")
        val author = commit?.optJSONObject("author")
        CompareCommit(
            sha = sha,
            message = commit?.optString("message").orEmpty(),
            author = author?.optString("name").orEmpty(),
            date = author?.optString("date").orEmpty().take(10),
        )
    }
}

private fun parseCompareFiles(arr: JSONArray?): List<CompareFile> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val f = arr.optJSONObject(i) ?: return@mapNotNull null
        val filename = f.optString("filename")
        if (filename.isBlank()) return@mapNotNull null
        CompareFile(
            filename = filename,
            previousFilename = f.optString("previous_filename").takeIf { it.isNotBlank() },
            status = f.optString("status"),
            additions = f.optInt("additions", 0),
            deletions = f.optInt("deletions", 0),
            patch = f.optString("patch"),
            blobUrl = f.optString("blob_url"),
        )
    }
}

/**
 * 把文件差异按「顶层目录」分组，用于对比页折叠展示。
 *
 * 保持 API 的原始文件顺序（GitHub 已按路径排序），空目录名归入根分组（key = ""）。
 */
fun groupCompareFiles(files: List<CompareFile>): List<Pair<String, List<CompareFile>>> {
    if (files.isEmpty()) return emptyList()
    val order = LinkedHashMap<String, MutableList<CompareFile>>()
    files.forEach { f ->
        // 根目录文件归入 "" 分组；其余按顶层目录分组（保留末尾 /）
        val slash = f.filename.indexOf('/')
        val key = if (slash < 0) "" else f.filename.substring(0, slash + 1)
        order.getOrPut(key) { mutableListOf() }.add(f)
    }
    return order.map { (dir, list) -> dir to list }
}
