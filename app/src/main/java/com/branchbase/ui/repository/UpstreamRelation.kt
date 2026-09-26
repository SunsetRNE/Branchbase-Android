package com.branchbase.ui.repository

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.branchbase.R

/**
 * 「上游」的**判定规则**：本仓库在 GitHub 复刻网络（fork network）里，above 它的那个仓库。
 *
 * ## 为什么要把「上游」从「让用户自己填」改成「自动探测」
 *
 * 1.1.3 之前，「上游…」这一出口只有一张手填表：远端地址 + 上游分支名。可这两件事里
 * **只有一件是用户的自由**（往哪推），另一件（这个仓库有没有上游、上游叫什么、上游还在不在）
 * 是**客观事实**，而且 API 已经写着 —— 让用户回忆一个已经能从仓库读出来的地址，
 * 结果是三种都见过：填成自己的仓库（推了个寂寞）、填成上游（推不上去，那本来就没写权限）、
 * 拼错 owner（`https://github.com/<repo>.git`，见下）。
 *
 * 所以这一版把「上游」拆成两件事：
 *
 * 1. **上游关系**（本文件的规则）：自动探测 —— 本仓库是不是复刻、复刻自谁、那个仓库现在的状态；
 * 2. **推送远端**（[UpstreamSetupScreen]）：仍然是本仓库自己的地址 —— 复刻推的必须是自己的复刻，
 *    不是上游（对别人的仓库没有写权限）。探测结果只用来**给一句话**，不改推送目的地。
 *
 * ## 六种状态与它们的证据
 *
 * | 状态 | 证据 | 能做什么 |
 * |---|---|---|
 * | [UpstreamState.SELF_OWNED] | `fork=false`（且没有 `parent`）—— 本仓库就是网络的源 | 没有「上游」这件事：入口整枚不画 |
 * | [UpstreamState.AVAILABLE] | `parent.full_name` 有值，且探得到（200、未归档） | 正常：可拉取、比对、往上游开 PR |
 * | [UpstreamState.ARCHIVED] | 同上，但 `archived=true` | 上游只读：仍可拉取比对，**不能**推、不能再开新 PR |
 * | [UpstreamState.DELETED] | `fork=true` 但 `parent` 没了 —— GitHub 在父仓库被删（或转私有并拆网）时**断开复刻关系** | 上游地址已失效：本仓库现在自己就是网络的源 |
 * | [UpstreamState.PRIVATE] | `parent` 还在复刻关系里，却读不到（404/无权限） | 关系还在但没有访问权：拉取 / 比对 / 开 PR 都会失败 |
 * | [UpstreamState.UNKNOWN] | 本仓库信息或令牌读不到 —— 不发请求、也不猜 | 如实说「不知道」，入口照旧、不藏功能 |
 *
 * 「已删除」与「已私有化」为什么能分开：GitHub 自己在[复刻文档](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/working-with-forks/what-happens-to-forks-when-a-repository-is-deleted-or-changes-visibility)
 * 里写明了 —— 父仓库被删、或公共仓库转私有，都会让复刻**断开成独立网络**（`parent` 随之消失）；
 * 反过来，`parent` 仍在、仓库却读不到，只剩「没权限」这一种解释。所以判定顺序是：
 * **先看复刻关系在不在，再看能不能读到**，而不是拿一次 404 去猜（`404` 本身是二义的）。
 *
 * 所有函数都是纯函数（无 IO、无 Android 依赖），可直接单测 —— 判定规则必须可回归。
 */

/** 「上游」的六种状态（口径见文件头）。 */
enum class UpstreamState {
    /** 自持仓库：不是复刻，本仓库就是复刻网络的源 —— 没有上游这件事。 */
    SELF_OWNED,

    /** 有上游仓库，且读得到、没归档。 */
    AVAILABLE,

    /** 上游已归档（GitHub 的 archived）：只读，不接受推送与新 PR。 */
    ARCHIVED,

    /** 上游已删除（复刻关系已断开）：上游地址不再可用。 */
    DELETED,

    /** 上游已私有化（复刻关系还在，但当前令牌读不到）。 */
    PRIVATE,

    /** 无法确认：本仓库信息或令牌读不到 —— 不猜。 */
    UNKNOWN,
}

/**
 * 一次上游判定：状态 + 上游的 `owner/name`（能知道时）。
 *
 * [fullName] 可空的分支：自持仓库（没有上游）、已断开（名字已随关系一起消失）、
 * 以及本仓库信息都读不到。**不要用空串冒充 null** —— 界面要按「有没有名字」决定画不画那一行。
 */
data class UpstreamRelation(
    val state: UpstreamState,
    val fullName: String? = null,
) {
    /**
     * 要不要在 Git 工作台里画「上游…」这枚出口。
     *
     * 自持仓库**不画**（1.1.5）：没有上游可谈，画一枚点进去只能说「你没有上游」的胶囊，
     * 就是本仓库一直在删的那种「点了没反应」的入口。设置列表里那一页仍然在（老路径不变），
     * 「设为上游并推送」也照旧在「同步」页 —— 这里少的只是一个本仓库不适用的出口。
     */
    val showsEntry: Boolean get() = state != UpstreamState.SELF_OWNED
}

/**
 * 上游探测的输入：一次 `GET /repos/{parent}` 的结果（1.1.5）。
 *
 * [attempted] / [denied] / [info] 是三件**不能互相冒充**的事：
 *
 * - 没探（没令牌）：[attempted] 假 —— 上游状态只能是「未知」；
 * - 探了、GitHub 回 404：[denied] 真 —— 复刻关系还在却读不到，这正是「已私有化」；
 * - 探了、但请求本身没打通（超时 / 限流 / 无响应）：三者都不给答案 —— 同样只能是
 *   「未知」。拿它去判「已私有化」，用户会看到一个自己并没有的结论。
 */
data class UpstreamProbe(
    val info: RepoInfo? = null,
    val attempted: Boolean = false,
    val denied: Boolean = false,
)

object UpstreamRules {

    /**
     * 判定。**顺序即优先级**：先看复刻关系在不在，再看上游读不读得到。
     *
     * @param isFork 本仓库是不是复刻（REST `fork` / GraphQL `isFork`）；null = 还没读到
     * @param parentFullName 复刻来源的 `owner/name`（REST `parent.full_name` / GraphQL `parent.nameWithOwner`）
     * @param probe 对上游仓库的那一次探测（[UpstreamProbe.attempted] = 真发过请求）
     * @param hasToken 当前会话有没有令牌 —— 没令牌时读不到私有仓库，不能算上游的问题
     */
    fun detect(
        isFork: Boolean?,
        parentFullName: String?,
        probe: UpstreamProbe = UpstreamProbe(),
        hasToken: Boolean = true,
    ): UpstreamRelation {
        val name = parentFullName?.takeIf { it.isNotBlank() }
        return when {
            // 本仓库自己都没读到：不发请求、也不猜（老缓存/首次进入都会走到这里）
            isFork == null -> UpstreamRelation(UpstreamState.UNKNOWN, name)

            // 不是复刻：本仓库就是源
            !isFork && name == null -> UpstreamRelation(UpstreamState.SELF_OWNED)

            // 复刻关系已断开（父仓库被删 / 转私有拆网）：GitHub 会把 parent 抹掉
            name == null -> UpstreamRelation(UpstreamState.DELETED)

            // 上游读得到：归档与否是两种形态（归档的仓库不接受推送与新 PR）
            probe.info != null ->
                UpstreamRelation(
                    if (probe.info.archived) UpstreamState.ARCHIVED else UpstreamState.AVAILABLE,
                    name,
                )

            // 读不到但关系还在 → 只剩「没有访问权」这一种解释；没令牌、或那次请求本身
            // 没打通，都如实说「不知道」，不替用户下结论
            !hasToken || !probe.attempted -> UpstreamRelation(UpstreamState.UNKNOWN, name)

            probe.denied -> UpstreamRelation(UpstreamState.PRIVATE, name)

            else -> UpstreamRelation(UpstreamState.UNKNOWN, name)
        }
    }

    /** [RepoInfo]/[RepoViewerRelation] 两条来源的合一：**先仓库详情，再关系态**（后者粒度更粗）。 */
    fun resolve(
        info: RepoInfo?,
        relation: RepoViewerRelation?,
    ): Pair<Boolean?, String?> = (info?.isFork ?: relation?.isFork) to
        (info?.parentFullName ?: relation?.parentFullName)

    /** `owner/name` → 两段。格式不对（带斜杠但不全、空串）返回 null，不硬拆。 */
    fun splitFullName(full: String?): Pair<String, String>? {
        val parts = full?.trim()?.split('/') ?: return null
        if (parts.size != 2) return null
        val (owner, name) = parts
        if (owner.isBlank() || name.isBlank()) return null
        return owner to name
    }
}

/** 状态标签（短，一格胶囊的宽度里放得下）。 */
@StringRes
internal fun upstreamStateLabelRes(state: UpstreamState): Int = when (state) {
    UpstreamState.SELF_OWNED -> R.string.state_upstream_self_owned
    UpstreamState.AVAILABLE -> R.string.state_upstream_available
    UpstreamState.ARCHIVED -> R.string.state_upstream_archived
    UpstreamState.DELETED -> R.string.state_upstream_deleted
    UpstreamState.PRIVATE -> R.string.state_upstream_private
    UpstreamState.UNKNOWN -> R.string.state_upstream_unknown
}

/**
 * 一句**针对性**说明（决策页与「后果弹窗」共用）。
 *
 * 按状态各写各的：归档与已删除的差别正是「还能不能推」，
 * 共用一句话的表现是用户照着「可拉取」去推一个归档仓库。
 */
@StringRes
internal fun upstreamNoteRes(state: UpstreamState): Int = when (state) {
    UpstreamState.SELF_OWNED -> R.string.note_upstream_self_owned
    UpstreamState.AVAILABLE -> R.string.note_upstream_available
    UpstreamState.ARCHIVED -> R.string.note_upstream_archived
    UpstreamState.DELETED -> R.string.note_upstream_deleted
    UpstreamState.PRIVATE -> R.string.note_upstream_private
    UpstreamState.UNKNOWN -> R.string.note_upstream_unknown
}

/**
 * 说明文案的最终形态：`AVAILABLE` 那种要带上游名字，其余是定句。
 *
 * 放在这里（而不是各调用点）是因为它有**三个**调用点（弹窗 / 决策页 / 日志），
 * 各写一遍必然漂成三句不同的话。
 */
@Composable
internal fun upstreamNoteText(rel: UpstreamRelation): String = when (rel.state) {
    UpstreamState.AVAILABLE ->
        stringResource(R.string.note_upstream_available, rel.fullName ?: "")

    UpstreamState.ARCHIVED ->
        stringResource(R.string.note_upstream_archived, rel.fullName ?: "")

    else -> stringResource(upstreamNoteRes(rel.state))
}

/**
 * Git 工作台分支下面那一行要不要画。
 *
 * 那一行只有两种内容：「跟踪到的远端分支」（`origin/main`）与「未设置上游」。
 * 后者是**说给有上游的仓库听的** —— 它在提示「你可以设一个上游」；而自持仓库（本仓库就是复刻
 * 网络的源，见 [UpstreamState.SELF_OWNED]）没有上游可设，同一句话落在那里就是一句永远无法
 * 执行的催促。所以复用 1.1.5 的探测结果：**没有上游就不显示，有就显示**。
 *
 * 两个细节：
 * - 判定与藏「上游…」那枚出口用的是同一份 [UpstreamRelation]，但**不是同一个开关**：
 *   出口看 [UpstreamRelation.showsEntry]，这一行还取决于当前分支有没有跟踪上远端 ——
 *   跟踪上了就必须画，因为「跟踪的是 `origin/xxx`」本身是要说的事实。
 * - [rel] 为 null（还没探到、或本仓库详情读不到）时**不许藏**：拿「不知道」去当「没有」，
 *   会让一个真有上游的仓库失去那句唯一提示（和 [UpstreamState.UNKNOWN] 的取舍一致）。
 *
 * @param hasUpstream 本地分支是否跟踪了远端分支（`LocalRepoGitState.hasUpstream`）
 * @param upstreamBranch 跟踪的远端分支名（`LocalRepoGitState.upstream`）；空串视同没跟踪
 * @param rel 上游探测结果；null = 还不知道
 */
internal fun showsUpstreamLine(
    hasUpstream: Boolean,
    upstreamBranch: String?,
    rel: UpstreamRelation?,
): Boolean {
    if (hasUpstream && !upstreamBranch.isNullOrBlank()) return true
    return rel?.state != UpstreamState.SELF_OWNED
}
