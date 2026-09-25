package com.branchbase.ui.repository

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 冲突**预解析**的进程内缓存（`git-mode-design.md` §6.4 的 D-h 契约）。
 *
 * 契约三条，这个类就是它们的实现：
 *
 * 1. **触发点是「冲突弹窗出现那一刻」**，不是用户点「查看并解决」时 —— 所以 [start]
 *    由宿主持有弹窗的那一刻调，而不是由详情页调；
 * 2. **结果落进程内缓存**（按仓库目录键控）：详情页只**读** [stateOf]，
 *    有结果直接渲染 / 还在算先出骨架 / 失败给重试；
 * 3. **两条不许**（写错的样子都是「同一个仓库算两遍」）：
 *    - 页面打开后再从头算 —— 页面**没有**自己的取数入口（它不调引擎）；
 *    - 把预解析做成页面状态 —— 所以它不是 `remember`，返回再进 [stateOf] 仍是上次那份。
 *
 * 取消走协程（引擎侧是纯本地读、秒级，没有自己的取消句柄）；[clear] 在合并收尾
 * （提交合并 / 放弃合并）时调 —— 不清的话下一次合并会先渲染上一次的冲突清单。
 */
internal class MergePreparseCache {

    // 状态表用**可观察**的 map：详情页直接读 `stateOf(...)`，算完之后要能自己重组
    // （普通 map 的表现是「预解析早就完成了，页面还一直显示『正在预解析』」）
    private val states = mutableStateMapOf<String, MergePreparseState>()
    private val jobs = mutableMapOf<String, Job>()

    /** 当前状态（没开始过 = [MergePreparseState.Idle]）。 */
    fun stateOf(repoDir: String): MergePreparseState =
        states[repoDir] ?: MergePreparseState.Idle

    /**
     * 开始预解析。已经在算 / 已经有结果时**什么都不做**（除非 [force]，重试用它）。
     *
     * @param fetch 真正取数的那一句（宿主传 `RustBridge.gitAnalyzeConflicts`）——
     *   做成参数是为了能在单测里注入一个假的，而不必去碰 Compose 或引擎
     */
    fun start(
        repoDir: String,
        scope: CoroutineScope,
        force: Boolean = false,
        fetch: suspend () -> String?,
    ) {
        if (repoDir.isBlank()) return
        if (!force) {
            when (stateOf(repoDir)) {
                is MergePreparseState.Loading, is MergePreparseState.Ready -> return
                MergePreparseState.Idle, MergePreparseState.Failed -> Unit
            }
        }
        jobs.remove(repoDir)?.cancel()
        states[repoDir] = MergePreparseState.Loading
        jobs[repoDir] = scope.launch {
            val analysis = parseMergeAnalysis(runCatching { fetch() }.getOrNull())
            states[repoDir] = if (analysis == null) {
                MergePreparseState.Failed
            } else {
                MergePreparseState.Ready(analysis)
            }
        }
    }

    /** 合并收尾（提交合并 / 放弃合并 / 仓库被别的动作改动）时清掉这一份。 */
    fun clear(repoDir: String) {
        jobs.remove(repoDir)?.cancel()
        states.remove(repoDir)
    }
}

/** 预解析的四种状态。 */
internal sealed interface MergePreparseState {
    /** 还没开始（弹窗还没出现过）。 */
    data object Idle : MergePreparseState

    /** 正在算（弹窗已出现、详情页还没打开时就是这个状态）。 */
    data object Loading : MergePreparseState

    data class Ready(val analysis: MergeAnalysis) : MergePreparseState

    /**
     * 算不出来（引擎读不到 / 仓库被改动过）。**如实说明 + 可重试** ——
     * 不画一个空列表：那会被读成「一个冲突都没有」。
     */
    data object Failed : MergePreparseState
}

/** 进程内唯一的一份（页面不持有它，也不重建它）。 */
internal object MergePreparse {
    private val cache = MergePreparseCache()

    fun stateOf(repoDir: String): MergePreparseState = cache.stateOf(repoDir)

    fun start(
        repoDir: String,
        scope: CoroutineScope,
        force: Boolean = false,
        fetch: suspend () -> String?,
    ) = cache.start(repoDir, scope, force, fetch)

    fun clear(repoDir: String) = cache.clear(repoDir)
}
