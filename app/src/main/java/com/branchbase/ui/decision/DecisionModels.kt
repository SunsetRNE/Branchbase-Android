package com.branchbase.ui.decision

import org.json.JSONArray
import org.json.JSONObject

/**
 * 决策页面数据模型（事实区模型：只描述客观状态，与决策呈现分离）。
 */

/** 仓库状态（nativeGitStatus JSON 解析结果） */
data class GitStatus(
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val hasUpstream: Boolean,
    val remoteUrl: String,
    val dirty: List<DirtyFile>,
    val unpushed: List<UnpushedCommit>,
    /**
     * HEAD 是否有父提交。**false = 这是第一个提交**：`reset --soft HEAD~1`（撤销上一次提交）
     * 必然失败 —— 页面要据此提前说明，而不是报「引擎不可用」。
     */
    val hasParent: Boolean = true,
    /** HEAD 的完整 sha（空串 = 未知）。用于与远端 ref 比对，判断「远端有没有变化」。 */
    val headSha: String = "",
    /**
     * `refs/remotes/origin/{branch}` 是否存在。**false** 时「放弃本地提交（reset --hard origin/x）」
     * 必然失败（从没 fetch 过的仓库就是这种）—— 同样要在 UI 里提前说明。
     */
    val hasRemoteRef: Boolean = false,
    /**
     * 仓库是不是**停在合并中**（`.git/MERGE_HEAD` 在）。
     *
     * 用途只有一个：让界面知道现在该给的是「继续 / 放弃合并」，而不是假装一切正常
     * （`git-mode-design.md` §6.4 风险 ①：合并到一半被杀之后，仓库就在这个状态里）。
     * 细节（还剩哪几个冲突文件、待提交的信息）走 `merge_state`，这个字段只是那个开关。
     */
    val merging: Boolean = false,
)

/** 工作区变更文件 */
data class DirtyFile(val path: String, val status: String)

/** 未推送提交 */
data class UnpushedCommit(val sha: String, val message: String)

/** 敏感信息命中 */
data class SensitiveHit(val line: Int, val kind: String, val mask: String)

/** 解析 gitStatus JSON（失败返回 null）。 */
fun parseGitStatus(json: String?): GitStatus? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        val dirty = mutableListOf<DirtyFile>()
        val dirtyArr = o.optJSONArray("dirty") ?: JSONArray()
        for (i in 0 until dirtyArr.length()) {
            val d = dirtyArr.optJSONObject(i) ?: continue
            dirty += DirtyFile(d.optString("path"), d.optString("status"))
        }
        val unpushed = mutableListOf<UnpushedCommit>()
        val upArr = o.optJSONArray("unpushed") ?: JSONArray()
        for (i in 0 until upArr.length()) {
            val u = upArr.optJSONObject(i) ?: continue
            unpushed += UnpushedCommit(u.optString("sha"), u.optString("message"))
        }
        GitStatus(
            branch = o.optString("branch", "main"),
            ahead = o.optInt("ahead", 0),
            behind = o.optInt("behind", 0),
            hasUpstream = o.optBoolean("has_upstream", false),
            remoteUrl = o.optString("remote_url", ""),
            dirty = dirty,
            unpushed = unpushed,
            // 缺键时的默认值偏保守：hasParent=true 不误报「第一个提交」，
            // hasRemoteRef=false 则让「放弃本地提交」在状态未知时先拦一下（见各决策页的预检）。
            hasParent = o.optBoolean("has_parent", true),
            headSha = o.optString("head_sha", ""),
            hasRemoteRef = o.optBoolean("has_remote_ref", false),
            // 缺键 = 老 `.so`：false（按「没在合并」退化 —— 这一条的代价只是少一个提示，
            // 而反过来把正常仓库画成「合并中」会让用户去找一个不存在的出路）
            merging = o.optBoolean("merging", false),
        )
    }.getOrNull()
}

/** 解析敏感扫描 JSON 数组。 */
fun parseSensitiveHits(json: String?): List<SensitiveHit> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(SensitiveHit(o.optInt("line"), o.optString("kind"), o.optString("mask")))
            }
        }
    }.getOrDefault(emptyList())
}
