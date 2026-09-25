package com.branchbase.ui.repository

/**
 * Git 工作台（气泡面板）的**日志锚点 tag**。
 *
 * 与 `Logging.kt` 的 `LOG_ANCHORS` 表是一份契约的两端：这里写字面量、那边写解释，
 * `LogAnchorsTest` 保证表里的每个 tag 都真的在源码里用过。抽成常量是为了**不写错** ——
 * tag 写歪一个字母日志照样打，但照表 grep 的人一条都搜不到。
 */
internal const val GIT_WORKBENCH_LOG_TAG = "Git工作台"

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

/**
 * 工作台视图的种类。
 *
 * **`available = false` 的档不装死也不装活**：标签上标「待接入」，点进去是一句如实的说明
 * （`GitPanelViewPlaceholder`）—— 既不放一个点了没反应的入口，也不假装它已经能用。
 */
enum class GitPanelKind(
    /**
     * 这一档是否已落地。
     *
     * 阶段 0 → 工作区；阶段 1 → 提交图；阶段 2 → 引用树；阶段 4 → 文件历史
     * （[`git-mode-design.md`](../../../../../../docs/specs/git-mode-design.md) §8）。
     * 用枚举而不是「按阶段删代码」：列表与测试都能跟着它走，落地时只改这一处。
     *
     * **落地了必须同步改这里**：阶段 1 把「提交图」渲染接上了，却漏了把 `available` 从
     * `false` 翻成 `true` —— 于是标签条上一直标着「待接入」，点进去却是一张能用的图
     * （自相矛盾，且没有任何测试拦得住，因为钉子钉的正是那个错值）。
     */
    val available: Boolean,
) {
    Workspace(available = true),
    Graph(available = true),
    Refs(available = true),
    FileHistory(available = true),
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

/**
 * 档位的**日志名**（稳定、与界面语言无关）。
 *
 * 标签条上那套中文名会跟着界面语言变（英文模式下是 Working tree / Commit graph…），
 * 而日志的约定是「固定中文 + 可 grep」—— 拿 label 当日志名的话，同一件事在两种语言下
 * 会变成两条不同的检索词。所以日志走枚举名：`Collapsed` / `Actions` / `View(Workspace)`。
 */
internal fun gitPanelStageLogName(stage: GitPanelStage): String = when (stage) {
    GitPanelStage.Collapsed -> "Collapsed"
    GitPanelStage.Actions -> "Actions"
    is GitPanelStage.View -> "View(${stage.kind.name})"
}

/**
 * 带 `openGitPanel` 的深链接进来时，面板**一上来就在哪一档**。
 *
 * 设置 → 本地仓库那一行点「进入」的落点（`git-mode-design.md` §3.4）：打开代码页，
 * 面板**直接展开到视图档**，而不是先落在动作列表再让用户自己点一次 ——
 * 用户从「本地仓库」过来时想看的就是工作台（分支 / 改动 / 引用），动作列表是中途站。
 *
 * 抽成纯函数是为了能单测：这一条写错的表现是「点了『进入』，面板关着」或
 * 「面板开着但停在动作列表」，两者都只有真机上点一次才看得出来。
 */
internal fun initialGitPanelStage(openGitPanel: Boolean): GitPanelStage =
    if (openGitPanel) GitPanelStage.View(GitPanelKind.Workspace) else GitPanelStage.Collapsed
