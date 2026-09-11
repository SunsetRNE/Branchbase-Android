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

/**
 * 危险操作级别：决定删除/放弃类操作是否需要升级警告或二次确认。
 */
enum class DangerLevel {
    /** 无风险：直接执行 + 轻反馈 */
    NONE,

    /** 需确认：普通 AlertDialog */
    CONFIRM,

    /** 需二次确认：独立确认页/勾选（不可恢复） */
    DOUBLE_CONFIRM,
}
