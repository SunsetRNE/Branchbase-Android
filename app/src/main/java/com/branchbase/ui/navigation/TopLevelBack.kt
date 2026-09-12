package com.branchbase.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 顶层（主界面 / 登录后引导页）按返回键的动作：**再按一次退出应用**。
 *
 * ## 为什么不是「回登录首页」
 *
 * 改造前顶层返回是「回登录首页（保留会话）」，再按一次才退出。这条设计的代价是：
 * **会话还在，却把用户丢回登录页** —— 重启应用又直接回到主界面（会话是持久化的），
 * 于是「登录页」成了一个和真实登录状态不符的死状态；用户唯一的解释是「我被登出了」。
 *
 * 现在改成「再按一次退出」：**不离开主界面**，第一次按给一条 Toast 提示，
 * [EXIT_CONFIRM_WINDOW_MS] 内再按一次才 `finish()`。这样既修掉「返回跳登录页」，
 * 又保留了原先「防误触退出」的产品意图 —— 误触一次不会退出，也不会跑到别的页面。
 *
 * ## 纯函数与副作用分开
 *
 * 「窗口内第二次才算退出」抽成 [shouldExitOnBack]，因为「按几次才退出」只有真机连按才试得出来，
 * 回归时最难发现；[rememberTopLevelBackAction] 只负责计时器、Toast 与 `finish()` 这些副作用。
 */

/** 双击退出窗口：2 秒是 Android 上的常见取值（够按第二下，又不至于把两次无关的返回并成一次）。 */
const val EXIT_CONFIRM_WINDOW_MS: Long = 2000L

/** 第一次按返回时的提示文案。 */
internal const val EXIT_CONFIRM_HINT: String = "再按一次返回退出应用"

/**
 * 这一次返回是否应该**退出应用**（纯函数，便于单测）。
 *
 * @param lastBackAtMs 上一次按返回的时间（[SystemClock.elapsedRealtime]）；0 = 还没按过
 * @param nowMs 本次按返回的时间
 * @param windowMs 双击窗口
 */
internal fun shouldExitOnBack(
    lastBackAtMs: Long,
    nowMs: Long,
    windowMs: Long = EXIT_CONFIRM_WINDOW_MS,
): Boolean = lastBackAtMs > 0L && nowMs - lastBackAtMs in 0L..windowMs

/**
 * 造一个「顶层返回」动作：第一次调用提示、窗口内再调用则退出 Activity。
 *
 * 返回的 lambda 由调用方挂到返回键上（通常与 [PageBackHandler] 组合），
 * 这样「什么时候算顶层」仍由各页面自己判断，这里只负责「顶层按下去了做什么」。
 */
@Composable
fun rememberTopLevelBackAction(windowMs: Long = EXIT_CONFIRM_WINDOW_MS): () -> Unit {
    val context = LocalContext.current
    val lastBackAt = remember { mutableStateOf(0L) }
    return remember(context, windowMs) {
        {
            val now = SystemClock.elapsedRealtime()
            if (shouldExitOnBack(lastBackAt.value, now, windowMs)) {
                context.findActivity()?.finish()
            } else {
                lastBackAt.value = now
                Toast.makeText(context, EXIT_CONFIRM_HINT, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * 从 Compose 的 Context 找回宿主 Activity。
 *
 * `LocalContext` 在绝大多数情况下就是 ComponentActivity，但弹层 / 包装过的 Context 不保证，
 * 所以沿 [ContextWrapper] 链往上找一层，找不到就不退出（绝不抛异常）。
 */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
