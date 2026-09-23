package com.branchbase.ui.main

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue

/**
 * 主界面「你刚才在哪」的寄存点（1.0.84）。
 *
 * ## 它解决什么
 *
 * 「添加账号」的登录页要整屏接管，于是 `state` 从 `LoggedIn` 变成 `AddAccountWelcome` 时，
 * `LoggedInGate → MainScreen` **整棵子树被销毁**；返回时重建，`selected` / `showProfile` /
 * `subPage` 全部回到初始值 —— 用户从「设置 → 账号管理」点进去的，回来却落在首页/个人主页。
 * 用户的直觉是「返回到我刚才那一页」，这个偏差就是这么来的。
 *
 * 寄存点用进程内单例，因此不受那棵子树销毁的影响（与 `AddAccountFlow` 同一模式）。
 *
 * ## 为什么不是 `rememberSaveable`
 *
 * `rememberSaveable` 的值存在**它所在那层的 SaveableStateRegistry** 里；那一层被销毁时，
 * 注册表随之注销，值也就没了。本仓 `PageTransitions.kt` 的 `rememberSaveableStateHolder`
 * 能保住的是「**保活**的兄弟页之间切换」，救不了「整棵子树被换掉」这种情形。
 */
object MainNavMemory {

    /** 主界面内部的落点。 */
    data class Route(
        val tab: MainTab = MainTab.HOME,
        /**
         * 是否停在个人页。它是个人页内部子页路由的**入口**，所以只存 tab 不够。
         */
        val onProfile: Boolean = false,
        /**
         * 个人页里的子页（设置 / 账号管理…），null = 个人页主页三 Tab 之一。
         * 用 `name` 存而不是枚举本身：宿主是 `profile` 模块的私有枚举，这里不该依赖它。
         */
        val profileSubPage: String? = null,
    )

    enum class MainTab { HOME, MESSAGES }

    private val _pending: MutableState<Route?> = mutableStateOf(null)

    /**
     * 已寄存、等待被主界面消费的落点。
     *
     * 读完即消费（见 [consume]）—— 否则用户下次正常启动还会被拽回「账号管理」，
     * 那又会变成一个新的「落点不对」。
     */
    val pending: Route? get() = _pending.value

    /** 记下「现在在哪」，供「添加账号」这类会整屏接管的功能返回时恢复。 */
    fun remember(route: Route) {
        _pending.value = route
    }

    /** 消费寄存的落点（取走并清空）。 */
    fun consume(): Route? {
        val r = _pending.value
        _pending.value = null
        return r
    }

    /** 主动清空（例如账号已被删除、目标不再存在）。 */
    fun clear() {
        _pending.value = null
    }

    /** 仅供单测：复位（进程内单例不该在测试之间串味）。 */
    internal fun resetForTest() {
        _pending.value = null
    }
}
