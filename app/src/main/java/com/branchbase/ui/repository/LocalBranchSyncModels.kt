package com.branchbase.ui.repository

import org.json.JSONArray

/** 本地分支（`nativeLocalBranches` 的输出元素）。 */
data class LocalBranchInfo(
    val name: String,
    val isHead: Boolean,
    val upstream: String,
    val ahead: Int,
    val behind: Int,
)

/** 远端分支（`nativeRemoteBranches` 的输出元素；`name` 已去掉 `origin/` 前缀）。 */
data class RemoteBranchInfo(
    val name: String,
    val local: String,
    val hasLocal: Boolean,
    val ahead: Int,
    val behind: Int,
)

/**
 * 本地分支相对其远端跟踪分支的同步状态。
 *
 * 判定顺序即优先级：未跟踪 → 分叉 → 领先 → 落后 → 已同步。
 * 分叉（两边都有独有提交）必须排在领先/落后之前，否则会被误判为「只差一次推送」。
 */
enum class SyncState { Synced, Ahead, Behind, Diverged, Untracked }

fun syncStateOf(branch: LocalBranchInfo): SyncState = when {
    branch.upstream.isBlank() -> SyncState.Untracked
    branch.ahead > 0 && branch.behind > 0 -> SyncState.Diverged
    branch.ahead > 0 -> SyncState.Ahead
    branch.behind > 0 -> SyncState.Behind
    else -> SyncState.Synced
}

/** 状态标签（列表右侧展示）。 */
val SyncState.label: String
    get() = when (this) {
        SyncState.Synced -> "已同步"
        SyncState.Ahead -> "可推送"
        SyncState.Behind -> "可拉取"
        SyncState.Diverged -> "分叉"
        SyncState.Untracked -> "未跟踪"
    }

fun parseLocalBranchInfos(json: String?): List<LocalBranchInfo> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            if (name.isBlank()) return@mapNotNull null
            LocalBranchInfo(
                name = name,
                isHead = o.optBoolean("is_head", false),
                upstream = o.optString("upstream"),
                ahead = o.optInt("ahead", 0),
                behind = o.optInt("behind", 0),
            )
        }
    }.getOrDefault(emptyList())
}

fun parseRemoteBranchInfos(json: String?): List<RemoteBranchInfo> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            if (name.isBlank()) return@mapNotNull null
            RemoteBranchInfo(
                name = name,
                local = o.optString("local"),
                hasLocal = o.optBoolean("has_local", false),
                ahead = o.optInt("ahead", 0),
                behind = o.optInt("behind", 0),
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * 远端有、本地没有的分支（需要「创建并跟踪」）。
 *
 * `hasLocal=false` 的远端分支才需要落地成本地分支；已跟踪的排除在外。
 */
fun remoteOnlyBranches(remotes: List<RemoteBranchInfo>): List<RemoteBranchInfo> =
    remotes.filter { !it.hasLocal && it.name != "HEAD" }

/** 一键同步的执行计划（纯逻辑，便于单测）：先推送领先分支，再拉取落后的当前分支。 */
data class SyncPlan(
    val toPush: List<String>,
    val toPull: String?,
    val diverged: List<String>,
    val untracked: List<String>,
) {
    val isEmpty: Boolean get() = toPush.isEmpty() && toPull == null
}

/**
 * 生成同步计划。
 *
 * - 可推送：领先且无落后（推送是叠加操作，安全）
 * - 可拉取：只有**当前分支**能直接拉取（libgit2 的 pull 只作用于 HEAD）；
 *   落后的非当前分支交给用户逐个「切换并拉取」，不隐式切分支
 * - 分叉 / 未跟踪：不自动处理，交给用户决策
 */
fun planSync(locals: List<LocalBranchInfo>): SyncPlan {
    val toPush = mutableListOf<String>()
    var toPull: String? = null
    val diverged = mutableListOf<String>()
    val untracked = mutableListOf<String>()
    locals.forEach { b ->
        when (syncStateOf(b)) {
            SyncState.Ahead -> toPush += b.name
            SyncState.Behind -> if (b.isHead) toPull = b.name
            SyncState.Diverged -> diverged += b.name
            SyncState.Untracked -> untracked += b.name
            SyncState.Synced -> Unit
        }
    }
    return SyncPlan(toPush, toPull, diverged, untracked)
}
