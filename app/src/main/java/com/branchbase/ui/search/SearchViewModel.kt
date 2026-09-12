package com.branchbase.ui.search

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

/**
 * 搜索页的**保留状态**（跨「进入结果 → 返回搜索页」存活）。
 *
 * ## 为什么需要它
 *
 * 搜索页是 `MainScreen` 路由里的一页：点进某个结果后路由变成仓库详情，**搜索页被销毁**。
 * 原来的搜索词 / 类型 / 排序 / 筛选全在 `remember` 里，返回时全部归零 ——
 * 用户看到的是「空的搜索框 + 空结果」，只能重新输一遍（这是搜索页最高频的抱怨）。
 *
 * 状态放 ViewModel（Activity 作用域）后，返回时仍在；结果不进这里（已有缓存层，
 * 返回时按缓存直出，命中即瞬时）。
 *
 * ## 请求序号
 *
 * 同页还负责 [`nextSeq`] / [`isCurrent`]：快速连点搜索、或改类型/排序后立刻再搜时，
 * 旧请求可能后到并覆盖新结果（还会提前把 loading 置回 false）。
 * 每次发起请求领一个序号，**回来时不是最新序号就丢弃**。
 */
class SearchViewModel : ViewModel() {

    val queryState = mutableStateOf("")
    val typeState = mutableStateOf("仓库")
    val sortState = mutableStateOf("最佳匹配")
    val sortKeyState = mutableStateOf("")
    val languageState = mutableStateOf<String?>(null)
    val advancedState = mutableStateOf<Map<String, String>>(emptyMap())

    /** 是否已经搜过（决定空态显示「未找到结果」还是「输入关键词开始搜索」）。 */
    val searchedState = mutableStateOf(false)

    private var seq = 0

    /** 领一个新序号（发起请求前调用）。 */
    fun nextSeq(): Int {
        seq += 1
        return seq
    }

    /** 这份响应还是不是最新的（不是就丢弃，避免旧结果覆盖新结果）。 */
    fun isCurrent(token: Int): Boolean = token == seq

    /** 返回页面时是否需要自动重搜（有搜索词且搜过）。 */
    fun hasPendingSession(): Boolean = searchedState.value && queryState.value.isNotBlank()
}
