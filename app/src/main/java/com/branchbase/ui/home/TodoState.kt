package com.branchbase.ui.home

/**
 * 首页「待处理」的四种界面状态。
 *
 * ## 为什么不复用 [com.branchbase.ui.LoadState]
 *
 * `LoadState` 的契约是「三态 + 容器固定高」，`loadStateOf` 的调用点铺在仓库页、面板、弹窗一堆地方；
 * 而这里的「失败」还带一个**动作**（重试），不是纯状态枚举该装的东西。新造一个局部枚举，
 * 换公共契约不动。若将来别的页面也要失败态，再把它提升成公共枚举、一次性改所有调用点。
 *
 * ## 为什么需要第四档
 *
 * 计数改成 `Int?` 之后，`null` 表示「这一路没取到」——**不是 0**。
 * 三态（Loading / Empty / Ready）表达不了「取完了但没拿到」，于是断网时首页会断言
 * 「都处理完了」：`LoadState.kt` 里那句「不能从列表空不空反推」正是这件事。
 */
enum class TodoState { Loading, Empty, Failed, Ready }

/**
 * 四态判定（纯函数，单测见 `TodoStateTest`）。
 *
 * [loading] 优先：手上可能还有上一轮的旧值（Tab 保活 + 5 分钟缓存），
 * 但这一轮还没结束，就不该拿旧值下结论。
 *
 * 判空必须**三项都明确为 0**；任一项是 `null` 就落到 [TodoState.Failed]，
 * 而不是「当作 0」——那正是要修的 bug。
 */
fun todoStateOf(loading: Boolean, unread: Int?, reviews: Int?, assigned: Int?): TodoState = when {
    loading -> TodoState.Loading
    listOf(unread, reviews, assigned).any { (it ?: 0) > 0 } -> TodoState.Ready
    unread == 0 && reviews == 0 && assigned == 0 -> TodoState.Empty
    else -> TodoState.Failed
}

/**
 * 没记忆过时该不该折叠。
 *
 * - 空 → 折叠：空态就是折叠态，一句「都处理完了」占 44dp，不再为「没事了」画一张卡片；
 * - 失败 → **展开**：重试是唯一的恢复入口，不能藏在折叠里；
 * - 有内容 → 展开：待办要显眼，不能被收起藏掉；
 * - 加载中 → **折叠**：首帧还不知道结果，先收起。否则空态用户每次启动都会先看到一屏骨架
 *   弹开、拿到「全 0」再收拢 —— 那一下比「晚 100ms 看到内容」难受得多。
 *   加载完若确实有待办，再带着回弹展开，反而是个「有东西了」的提示。
 */
fun todoCollapsedByDefault(state: TodoState): Boolean = when (state) {
    TodoState.Empty, TodoState.Loading -> true
    TodoState.Failed, TodoState.Ready -> false
}

/**
 * 实际折叠状态：**记忆优先**，但失败态临时覆盖记忆。
 *
 * 用户手动收起的意图要记住（按账号存，见 `TodoFoldStore`）；只有 Failed 例外——
 * 那种状态下把重试藏起来，等于让用户没有出路。恢复后自动回到记忆值。
 */
fun todoCollapsed(state: TodoState, remembered: Boolean?): Boolean =
    if (state == TodoState.Failed) false else remembered ?: todoCollapsedByDefault(state)

/**
 * 折叠头摘要里的数字：已知项之和。
 *
 * 未知项按 0 计——这是**展示**上的取舍，不能反过来影响 [todoStateOf] 的判空
 * （那边 `null` 必须落到 Failed）。部分成功时摘要显示已知部分，行上未知项显示「—」。
 */
fun todoSummaryCount(unread: Int?, reviews: Int?, assigned: Int?): Int =
    (unread ?: 0) + (reviews ?: 0) + (assigned ?: 0)
