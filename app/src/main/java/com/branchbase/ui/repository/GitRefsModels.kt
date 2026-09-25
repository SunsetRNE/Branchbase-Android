package com.branchbase.ui.repository

import com.branchbase.core.RustBridge

/**
 * 「引用树」档（[GitPanelKind.Refs]）的视图模型。
 *
 * 数据全部来自**本地仓库**（`local_branches` / `remote_branches`，阶段 2 零 Rust 改动）——
 * 与「工作区」档同源、离线可读、不消耗 API 限额。tags 不在这一批里：本地 `list_tags`
 * 是阶段 3 的引擎新增项，这一档先如实标「按阶段接入」，**不用 REST 的 `/tags` 顶替** ——
 * 那会立刻出现第二个数据源（D-f 的字段口径也不一样），阶段 3 落地时反而要拆两遍。
 */
internal data class GitRefsView(
    val locals: List<GitRefRow>,
    val remotes: List<GitRemoteRefRow>,
) {
    /** 远端有、本地没有的分支数（「只在远端」的提示要用它，别在 UI 里再数一遍）。 */
    val remoteOnly: Int get() = remotes.count { !it.hasLocal }

    val isEmpty: Boolean get() = locals.isEmpty() && remotes.isEmpty()
}

/** 一行本地分支。 */
internal data class GitRefRow(
    val name: String,
    /** HEAD 所在分支 —— 面板里必须一眼看得出来（列表按它置顶）。 */
    val isHead: Boolean,
    val upstream: String,
    /** 紧凑徽标「↑2」「↓3」「↑2 ↓3」；已同步 / 未跟踪时为 null。 */
    val badge: String?,
    /** 有没有上游。**false 时不许画成「已同步」** —— 没配上游与「推完了」是两件事。 */
    val tracked: Boolean,
)

/** 一行远端跟踪引用（`name` 已去掉 `origin/` 前缀）。 */
internal data class GitRemoteRefRow(
    val name: String,
    /** 本地有没有对应分支。false = 只在远端（要「创建并跟踪」才落到本地）。 */
    val hasLocal: Boolean,
    val badge: String?,
)

/**
 * 领先 / 落后的紧凑徽标。两边都是 0 时返回 `null`。
 *
 * 不返回「↑0 ↓0」：那在面板里既不提供信息，又会被当成一种状态读 ——
 * 与「未跟踪」要分开表达（未跟踪是 `tracked = false`，不是 0/0）。
 */
internal fun refSyncBadge(ahead: Int, behind: Int): String? = when {
    ahead > 0 && behind > 0 -> "↑$ahead ↓$behind"
    ahead > 0 -> "↑$ahead"
    behind > 0 -> "↓$behind"
    else -> null
}

/**
 * 把引擎的两份列表整理成一档视图要画的东西（纯函数，便于单测）。
 *
 * 两条整理规则：
 * - **HEAD 置顶**：引擎本来就按 `is_head` 排过，这里再保证一次 —— 面板只有 268dp 宽、
 *   一屏放不下几个分支，「当前在哪」必须不用滚动就看到。Kotlin 的排序是稳定的，
 *   所以置顶之外**保持引擎给的顺序**（引擎按名字排）。
 * - 「只在远端」用 [remoteOnlyBranches] 同一份判据（`hasLocal = false` 且不是 `HEAD`），
 *   不在这里另写一遍。
 */
internal fun refsViewOf(
    locals: List<LocalBranchInfo>,
    remotes: List<RemoteBranchInfo>,
): GitRefsView = GitRefsView(
    locals = locals
        .sortedByDescending { it.isHead }
        .map { b ->
            GitRefRow(
                name = b.name,
                isHead = b.isHead,
                upstream = b.upstream,
                badge = refSyncBadge(b.ahead, b.behind),
                tracked = b.upstream.isNotBlank(),
            )
        },
    remotes = remotes.map { r ->
        GitRemoteRefRow(
            name = r.name,
            hasLocal = r.hasLocal,
            badge = refSyncBadge(r.ahead, r.behind),
        )
    },
)

/**
 * 读取本地仓库的引用（工作树不参与）。
 *
 * `null` = 读不到（仓库不存在 / 引擎不可用）—— 调用方据此显示失败态，
 * **不要**折成空列表：空列表是有意义的另一件事（仓库里真的没有引用）。
 */
internal suspend fun loadGitRefsView(repoDir: String): GitRefsView? {
    val localsJson = RustBridge.localBranches(repoDir) ?: return null
    val remotesJson = RustBridge.remoteBranches(repoDir) ?: return null
    return refsViewOf(parseLocalBranchInfos(localsJson), parseRemoteBranchInfos(remotesJson))
}
