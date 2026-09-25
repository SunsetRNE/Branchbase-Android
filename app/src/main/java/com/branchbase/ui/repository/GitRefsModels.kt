package com.branchbase.ui.repository

import com.branchbase.core.RustBridge
import org.json.JSONArray

/**
 * 「引用树」档（[GitPanelKind.Refs]）的视图模型。
 *
 * 数据全部来自**本地仓库**（`local_branches` / `remote_branches` / `list_tags`）——
 * 与「工作区」档同源、离线可读、不消耗 API 限额。tags 在 1.0.97 接上（阶段 3 的引擎新增项），
 * 走的是本地 `list_tags`，**没有**用 REST 的 `/tags` 顶替 —— 那会立刻出现第二个数据源
 * （D-f 的字段口径也不一样，annotated 与轻量 tag 的表达完全不同）。
 */
internal data class GitRefsView(
    val locals: List<GitRefRow>,
    val remotes: List<GitRemoteRefRow>,
    val tags: List<GitTagRow> = emptyList(),
) {
    /** 远端有、本地没有的分支数（「只在远端」的提示要用它，别在 UI 里再数一遍）。 */
    val remoteOnly: Int get() = remotes.count { !it.hasLocal }

    val isEmpty: Boolean get() = locals.isEmpty() && remotes.isEmpty() && tags.isEmpty()
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

/**
 * 一行 tag（D-f：**取全字段**，非 annotated 的那几项**留空、不填假值**）。
 *
 * [annotated] 决定这一行怎么显示：annotated tag 能点开看说明与打 tag 的人；
 * 轻量 tag 只有名字与提交 —— 给它编一个 tagger 等于在界面上撒谎，
 * 所以 [tagger]/[message] 为空时界面**不画**那两行，而不是画「未知」。
 */
internal data class GitTagRow(
    val name: String,
    val sha: String,
    val annotated: Boolean,
    val targetSha: String,
    val taggerName: String,
    val taggerEmail: String,
    val taggerTime: String,
    val message: String,
) {
    /** 说明的首行（tag 消息常常是多行，列表里只放第一行）。 */
    val subject: String get() = message.lineSequence().firstOrNull()?.trim().orEmpty()
}

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
    tags: List<GitTagRow> = emptyList(),
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
    // 引擎已按名字排好；这里保持原序（tag 没有「当前」这种要置顶的东西）
    tags = tags,
)

/**
 * 读取本地仓库的引用（工作树不参与）。
 *
 * `null` = 读不到（仓库不存在 / 引擎不可用）—— 调用方据此显示失败态，
 * **不要**折成空列表：空列表是有意义的另一件事（仓库里真的没有引用）。
 *
 * tags 走阶段 3 的本地 `list_tags`（D-f 全字段）：**不再用 REST `/tags` 顶替** ——
 * 那份给不了 annotated 的 tagger / 时间 / 说明，两套口径混用迟早要拆两遍。
 */
internal suspend fun loadGitRefsView(repoDir: String): GitRefsView? {
    val localsJson = RustBridge.localBranches(repoDir) ?: return null
    val remotesJson = RustBridge.remoteBranches(repoDir) ?: return null
    val tagsJson = RustBridge.gitListTags(repoDir) ?: return null
    return refsViewOf(
        parseLocalBranchInfos(localsJson),
        parseRemoteBranchInfos(remotesJson),
        parseLocalTags(tagsJson),
    )
}

/**
 * 解析本地 `list_tags` 的输出（扁平 native JSON）。
 *
 * 容错口径与其它解析一致：解析不出来返回空列表，不抛 —— 面板按「没有 tag」渲染。
 * 但**字段缺失不编值**：`annotated=false` 的条目即使带了 tagger 也按「没有」处理，
 * 免得把引擎的一处 bug 变成界面上的一句假话。
 */
internal fun parseLocalTags(json: String?): List<GitTagRow> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            if (name.isBlank()) return@mapNotNull null
            val annotated = o.optBoolean("annotated", false)
            val tagger = if (annotated) o.optJSONObject("tagger") else null
            GitTagRow(
                name = name,
                sha = o.optString("sha"),
                annotated = annotated,
                targetSha = o.optString("target_sha"),
                taggerName = tagger?.optString("name").orEmpty(),
                taggerEmail = tagger?.optString("email").orEmpty(),
                taggerTime = tagger?.optString("time").orEmpty(),
                message = if (annotated) o.optString("message") else "",
            )
        }
    }.getOrDefault(emptyList())
}
