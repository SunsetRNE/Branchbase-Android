package com.branchbase.ui.repository

import com.branchbase.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地合并与冲突解决的视图模型（阶段 5 的 UI 那半）。
 *
 * 引擎那半的条款见 `git-mode-design.md` §6.4 / §7，接口实现在 `core/src/git/mod.rs`
 * （`merge_branch` / `merge_state` / `analyze_conflicts` / `resolve_conflict` /
 * `write_resolved` / `merge_continue` / `merge_abort`）。
 *
 * 这里只做两件事：**把引擎的 JSON 变成模型**、**把「界面上要回答的问题」变成纯函数** ——
 * 两件都能在 JVM 单测里钉住，而它们写错的样子（「点了合并没反应」「冲突数永远是 0」）
 * 都只有真机上点一次才看得出来。
 */

/**
 * `merge_branch` 的四条出口 + 一条失败。
 *
 * **`Conflict` 不是失败**：仓库停在合并中，`conflicts` 是还没解决的文件清单 ——
 * 界面要引导「解决 → 提交合并 / 放弃合并」，而不是弹一句「合并失败」。
 */
sealed interface MergeOutcome {
    /** 当前分支已经包含对方（什么都不用做）。 */
    data object UpToDate : MergeOutcome

    /** 快进：当前分支直接指到对方，**没有产生合并提交**。 */
    data class FastForward(val sha: String) : MergeOutcome

    /** 干净合并：落了**两父**合并提交。 */
    data class Merged(val sha: String, val message: String) : MergeOutcome

    /** 有冲突：仓库停在合并中。 */
    data class Conflict(
        val branch: String,
        val message: String,
        val conflicts: List<String>,
    ) : MergeOutcome

    /**
     * 没跑起来（工作区脏 / 浅克隆 / 已在合并中 / 找不到分支 / 引擎不可用）。
     *
     * [reason] 是**引擎原话**（引擎的文案本来就是中文，与其它决策页同一条口径：原样透出）。
     * `null` = 引擎回了一句看不懂的东西 —— 那种情况界面用自己的资源文案，
     * **不在这层写死中文**（这一层没有 Context，写死的字面量在英文界面下照样是中文）。
     */
    data class Failed(val reason: String?) : MergeOutcome
}

/**
 * 解析 `RustBridge.gitMerge` 的原始输出。
 *
 * 这一条**不吞 `ERROR:` 前缀**（见 `RustBridge.gitMerge` 的说明）：失败原因
 * （工作区脏 / 浅克隆 / 上一次合并没结束 / 找不到分支）是界面必须如实显示的一句话，
 * 所以这里把它原样放进 [MergeOutcome.Failed]。
 *
 * 解析不出来时同样落到 [MergeOutcome.Failed]（而不是抛）：上层按一种出口处理即可。
 */
fun parseMergeOutcome(raw: String): MergeOutcome {
    if (raw.startsWith("ERROR:")) {
        return MergeOutcome.Failed(raw.removePrefix("ERROR:").trim().take(300))
    }
    return runCatching {
        val o = JSONObject(raw)
        val branch = o.optString("branch")
        val message = o.optString("message")
        when (o.optString("outcome")) {
            "up_to_date" -> MergeOutcome.UpToDate
            "fast_forward" -> MergeOutcome.FastForward(o.optString("head_sha"))
            "merged" -> MergeOutcome.Merged(o.optString("head_sha"), message)
            "conflict" -> MergeOutcome.Conflict(branch, message, stringList(o.optJSONArray("conflicts")))
            else -> MergeOutcome.Failed(null)
        }
    }.getOrElse { MergeOutcome.Failed(null) }
}

/** 一个冲突文件的**粗粒度类型**（引擎的 `kind`，见 `conflict_blobs`）。 */
enum class MergeConflictKind(val wire: String, val labelRes: Int) {
    BOTH_MODIFIED("both_modified", R.string.label_conflict_both_modified),
    BOTH_ADDED("both_added", R.string.label_conflict_both_added),
    DELETED_BY_US("deleted_by_us", R.string.label_conflict_deleted_by_us),
    DELETED_BY_THEM("deleted_by_them", R.string.label_conflict_deleted_by_them),
    UNKNOWN("", R.string.label_conflict_unknown);

    companion object {
        fun of(wire: String): MergeConflictKind =
            entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/** 当前合并状态（`merge_state`）里的一条冲突。 */
data class MergeConflictFile(val path: String, val kind: MergeConflictKind)

/**
 * 当前合并状态。`merging = false` 时其余字段没有意义（引擎仍会填 head/branch）。
 */
data class MergeState(
    val merging: Boolean,
    val branch: String,
    val headSha: String,
    val mergeHeadSha: String,
    val message: String,
    /** **还没解决**的文件（已解决的不会出现在这里 —— 引擎不落盘「合并开始时有哪些」）。 */
    val conflicts: List<MergeConflictFile>,
)

/** 解析 `merge_state` 的 JSON；null = 读不到（仓库不存在 / 引擎不可用）。 */
fun parseMergeState(json: String?): MergeState? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        val conflicts = mutableListOf<MergeConflictFile>()
        val arr = o.optJSONArray("conflicts") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            conflicts += MergeConflictFile(
                path = c.optString("path"),
                kind = MergeConflictKind.of(c.optString("kind")),
            )
        }
        MergeState(
            merging = o.optBoolean("merging", false),
            branch = o.optString("branch"),
            headSha = o.optString("head_sha"),
            mergeHeadSha = o.optString("merge_head_sha"),
            message = o.optString("message"),
            conflicts = conflicts,
        )
    }.getOrNull()
}

/**
 * 预解析出来的一个冲突文件（`analyze_conflicts`）。
 *
 * [patch] 是 **ours ↔ theirs** 的 unified diff：冲突块就是它的 hunk ——
 * 引擎不再单独算一套「冲突块」，两处各算一份只会出现两套互相矛盾的范围。
 */
data class MergeConflictDetail(
    val path: String,
    val kind: MergeConflictKind,
    /** 二进制文件：`patch` 为空（libgit2 不给二进制内容），界面要说「请选一边」。 */
    val binary: Boolean,
    val oursSha: String,
    val theirsSha: String,
    val baseSha: String,
    val oursSize: Int,
    val theirsSize: Int,
    val baseSize: Int,
    val patch: String,
    /** patch 被截断（超过引擎的 200 KB 上限）。 */
    val truncated: Boolean,
    /** 我方那一份的内容（超 [`contentTruncated`] 上限时是开头一段）。 */
    val ours: String,
    /** 对方那一份的内容。 */
    val theirs: String,
    /**
     * **工作区当前那一份**（带 `<<<<<<<` 冲突标记）—— 手工编辑的初值就该是它：
     * git 留给人的就是这个形状，从空文本开始编辑等于让用户自己把两边再抄一遍。
     */
    val worktree: String,
    /** 三方内容里有被截断的（与 patch 的 [truncated] 分开记 —— 两件事，别混成一个标志）。 */
    val contentTruncated: Boolean,
)

/** 预解析结果。 */
data class MergeAnalysis(val files: List<MergeConflictDetail>, val truncated: Boolean) {
    fun detailOf(path: String): MergeConflictDetail? = files.firstOrNull { it.path == path }
}

/** 解析 `analyze_conflicts` 的 JSON；null = 读不到。 */
fun parseMergeAnalysis(json: String?): MergeAnalysis? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        val files = mutableListOf<MergeConflictDetail>()
        val arr = o.optJSONArray("files") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            files += MergeConflictDetail(
                path = f.optString("path"),
                kind = MergeConflictKind.of(f.optString("kind")),
                binary = f.optBoolean("binary", false),
                oursSha = f.optString("ours_sha"),
                theirsSha = f.optString("theirs_sha"),
                baseSha = f.optString("base_sha"),
                oursSize = f.optInt("ours_size", 0),
                theirsSize = f.optInt("theirs_size", 0),
                baseSize = f.optInt("base_size", 0),
                patch = f.optString("patch"),
                truncated = f.optBoolean("truncated", false),
                // 老 `.so` 没有这几个键：缺了就是空串，界面据此如实说「内容读不到」，
                // 而不是把空串当内容画出来（那会让人以为文件是空的）
                ours = f.optString("ours"),
                theirs = f.optString("theirs"),
                worktree = f.optString("worktree"),
                contentTruncated = f.optBoolean("content_truncated", false),
            )
        }
        MergeAnalysis(files, o.optBoolean("truncated", false))
    }.getOrNull()
}

/**
 * 界面上那句「还剩几个」要用的口径：**只算还没解决的**。
 *
 * 已解决的那些引擎不落盘、也不重算（`merge_state` 的说明），所以「本次一共几个」
 * 只能来自合并那一刻的响应 —— 由调用方（宿主）拿着 [MergeOutcome.Conflict]，不要在这里编。
 */
internal fun unresolvedCount(state: MergeState?): Int = state?.conflicts?.size ?: 0

/**
 * 能不能「提交合并」：在合并中、且**一个冲突都不剩**。
 *
 * 抽成纯函数是因为它同时被三个地方判断（按钮可用态、点击时的预检、单测）——
 * 三处各写一遍的话，「还有 1 个没解决却让提交」这种漏网只会在真机上变成一句引擎报错。
 */
internal fun canContinueMerge(state: MergeState?): Boolean =
    state != null && state.merging && state.conflicts.isEmpty()

/** 一个冲突文件的解析动作。 */
enum class MergeResolveSide(val wire: String) {
    /** 用我方（当前分支）那一份。 */
    OURS("ours"),

    /** 用对方（被合并的分支）那一份。 */
    THEIRS("theirs"),
}

/**
 * 合并提交信息的**默认值**：引擎在 `.git/MERGE_MSG` 里写下的那一句（通常是
 * `Merge branch 'x'`）。空串 = 到时候让引擎自己取（`merge_continue` 的契约）。
 * 界面上预填它，用户可以改 —— 改了就按用户那句提交。
 */
internal fun defaultMergeMessage(state: MergeState?): String = state?.message.orEmpty()

/** `JSONArray` → `List<String>`（引擎给的是纯字符串数组）。 */
private fun stringList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotBlank()) add(s)
        }
    }
}
