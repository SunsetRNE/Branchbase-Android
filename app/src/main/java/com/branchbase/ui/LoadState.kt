package com.branchbase.ui

/**
 * 「还没加载完 / 加载完了是空的 / 有内容」—— 界面上的**三态**。
 *
 * ## 为什么单独立一个枚举
 *
 * 这一层是「弹窗与面板的占位」那次返工的根因：拿 `list.isEmpty()` 当加载判据时，
 * 「还没加载」与「加载完了确实没有」在代码里是**同一个分支**，于是
 * ① 加载中画的是「没有数据」的文案（或反过来，永远显示「加载中」）；
 * ② 两种状态的高度往往差很多，数据一到就把容器撑高。
 *
 * 三态分开之后，占位（[Loading]）与空态（[Empty]）各自有自己的画法与高度，
 * 而且**都在同一个容器尺寸里**（见 `ui/theme/Motion.kt` 的 `SkeletonRows`
 * 与 `git-mode-design.md` §3.5）。
 *
 * ⚠️ 判据必须是**显式的加载标志**，不能从「列表空不空」反推：
 * 反推的做法在「真的没有数据」时会把界面永远钉在加载态。
 */
enum class LoadState {
    /** 还在取数（或还没开始取）。**此时不做任何「没有数据」的结论**。 */
    Loading,

    /** 取数完成，结果确实是空的。 */
    Empty,

    /** 取数完成，有内容。 */
    Ready,
}

/**
 * 三态判定（纯函数，单测在 `LoadStateTest`）。
 *
 * [loading] 优先：即使手上已经有一份旧数据（[count] > 0，比如缓存直出后正在回源），
 * 这一轮也仍然算 [LoadState.Loading] —— 调用方据此决定「哪些部分可以先用旧数据画、
 * 哪些部分要出占位」，而不是把旧数据当最终结果。
 */
fun loadStateOf(loading: Boolean, count: Int): LoadState = when {
    loading -> LoadState.Loading
    count > 0 -> LoadState.Ready
    else -> LoadState.Empty
}
