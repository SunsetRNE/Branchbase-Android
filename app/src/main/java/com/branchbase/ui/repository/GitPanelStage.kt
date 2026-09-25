package com.branchbase.ui.repository

/**
 * Git 气泡面板的**三档状态**（对齐 [`git-mode-design.md`](../../../../../../docs/specs/git-mode-design.md) §3）。
 *
 * ```
 * Collapsed ──点球──► Actions ──点「工作区」等──► View(工作区)
 *     ▲                   ▲                          │
 *     └────── 点空白 ──────┴──────── 返回 / 面板内返回 ┘
 * ```
 *
 * ## 为什么把三档做成一个 sealed 状态，而不是两个 Boolean
 *
 * 那样会立刻出现非法组合（`expanded && view != null && !actionsVisible`）与「先设哪个」的时序问题 ——
 * 而这一页的历史上已经付过一次代价：消息页的显示模式两个入口各持一份 `remember`，
 * 只在页面活着时复现。**一个状态、一处渲染**是这里唯一稳的写法。
 *
 * 三档也是[返回键链路](GitPanelStage.kt)的层：L2 → L1 → 收起 → 页面（注册顺序即优先级）。
 */
sealed interface GitPanelStage {

    /** 只有 52dp 圆手柄。 */
    data object Collapsed : GitPanelStage

    /** 现有的动作列表（模式 / 分支 / 对比 / 同步 / 刷新 + 入口）。 */
    data object Actions : GitPanelStage

    /** 工作台的一档视图（阶段 0 先有「工作区」，其余按阶段接上）。 */
    data class View(val kind: GitPanelKind) : GitPanelStage
}

/** 工作台视图的种类。**`available = false` 的档不许渲染成可点**（点了没反应的入口比没有更坏）。 */
enum class GitPanelKind(
    /**
     * 这一档是否已落地。
     *
     * 阶段 0 只有「工作区」；提交图 / 引用树 / 文件历史分别在阶段 1 / 2 / 4 接上
     * （[`git-mode-design.md`](../../../../../../docs/specs/git-mode-design.md) §9）。
     * 用枚举而不是「按阶段删代码」：列表与测试都能跟着它走，落地时只改这一处。
     */
    val available: Boolean,
) {
    Workspace(available = true),
    Graph(available = false),
    Refs(available = false),
    FileHistory(available = false),
}

/**
 * 面板层级：折叠 0 / 动作 1 / 视图 2。
 *
 * 纯函数，供两处共用：**返回键退回一档**与**切换动效的方向判定** ——
 * 两处各自写一遍的话，方向写反只会表现成「动画照播但方向不对」，很难查。
 */
internal fun panelDepth(stage: GitPanelStage): Int = when (stage) {
    GitPanelStage.Collapsed -> 0
    GitPanelStage.Actions -> 1
    is GitPanelStage.View -> 2
}

/** 返回一档：视图 → 动作列表 → 收起；已在最外层时原地不动（返回键不该把面板弹开）。 */
internal fun panelBack(stage: GitPanelStage): GitPanelStage = when (stage) {
    GitPanelStage.Collapsed -> GitPanelStage.Collapsed
    GitPanelStage.Actions -> GitPanelStage.Collapsed
    is GitPanelStage.View -> GitPanelStage.Actions
}

/**
 * 方向：`+1` 进档 / `-1` 退档 / `0` 同级（同一层的两档视图之间切换）。
 *
 * 与页面级的 `pageDirection` 同一语义，但**不共用函数**：页面层级是 `PageLevel.depth`，
 * 面板层级是这里的三档 —— 硬凑成一个函数只会让两边都难改。
 */
internal fun panelDirection(from: GitPanelStage, to: GitPanelStage): Int {
    val delta = panelDepth(to) - panelDepth(from)
    return when {
        delta > 0 -> 1
        delta < 0 -> -1
        else -> 0
    }
}
