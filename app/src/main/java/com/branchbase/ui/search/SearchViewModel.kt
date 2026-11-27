package com.branchbase.ui.search

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

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
 *
 * ## 选项记忆
 *
 * 类型 / 排序 / 语言 / 高级筛选由 [SearchOptionStore] 落盘，构造函数在**首次创建时**读一次：
 * 这样首帧画出来就是上次的矩阵，不会先闪一帧默认值再跳成记忆值（在组合期写状态做恢复会）。
 */
class SearchViewModel internal constructor(restored: SearchOptions? = null) : ViewModel() {

    val queryState = mutableStateOf("")
    val typeState = mutableStateOf(restored?.type ?: SearchType.REPOS)
    val sortKeyState = mutableStateOf(restored?.sortKey ?: "")
    val languageState = mutableStateOf<String?>(restored?.language)
    val advancedState = mutableStateOf<Map<String, String>>(restored?.advanced ?: emptyMap())

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

    companion object {
        /**
         * 带记忆的工厂。`viewModel(factory = …)` 只在**没有现成实例**时用这个工厂，
         * 所以恢复只会发生一次；之后改选项走 [typeState] 等状态，不会被记忆覆盖回去。
         */
        internal fun factory(restored: SearchOptions?): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(restored) }
        }
    }
}
