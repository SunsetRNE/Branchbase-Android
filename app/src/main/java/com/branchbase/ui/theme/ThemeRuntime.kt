package com.branchbase.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题模式的运行时状态。
 *
 * ## 为什么要有这一层
 *
 * 开关分散在各处（登录首页右上角的太阳图标、设置页的「外观」行），
 * 而**真正的渲染**在 `MainActivity` 的根 composable。如果靠参数把 setter 一层层传下去，
 * 每个中间页面都得跟着加参数（这个 App 的页面层级有 3~4 层）。
 *
 * 这里用一个进程内 StateFlow 收口：任何地方 `ThemeRuntime.cycle(context)`，
 * 根节点订阅 [mode] 自动重组 —— 开关与被渲染的主题之间不需要任何参数管道。
 */
object ThemeRuntime {

    private val _mode = MutableStateFlow(ThemeMode.default)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    /** 启动时同步一次持久化的档位（由 `MainActivity` 调用）。 */
    fun init(context: Context) {
        _mode.value = ThemeSettings.read(context)
    }

    fun set(context: Context, mode: ThemeMode) {
        ThemeSettings.write(context, mode)
        _mode.value = mode
    }

    /** 太阳图标开关：跟随系统 → 浅色 → 深色 → 跟随系统。 */
    fun cycle(context: Context): ThemeMode {
        val next = _mode.value.next()
        set(context, next)
        return next
    }
}
