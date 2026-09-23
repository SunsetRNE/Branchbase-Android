package com.branchbase.ui.auth

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue

/**
 * 「正在为已有账号**新增**一个登录」的瞬时状态（1.0.79）。
 *
 * ## 它修的是什么
 *
 * 账号页的「添加账号」原先直接接的是 `onLogout`（见 `ProfileScreen` 的 `SubPage.Accounts` 那行）——
 * 于是点「添加账号」＝**把用户登出**：`LoginViewModel.logout()` 删掉全局 `session` 键、
 * 状态回到欢迎页。后果有两个，用户都撞上了：
 *
 * 1. **回不去**：欢迎页刻意不拦截返回键（未登录时「再按一次退出 App」），所以从账号页进来的人
 *    既回不到账号页，按返回还可能直接退出应用；
 * 2. **凭据被误删**：只是想再加一个号，当前账号的 `session` 却已经被清掉了 ——
 *    这与「刷新态不该夺权」是同一条原则（见 `AccountStore.planUpsert`）。
 *
 * ## 为什么用进程内单例而不是层层传参
 *
 * 触发点在很深的地方（`AccountsScreen` ← `ProfileScreen` ← `MainScreen`），
 * 而分流点在根（`LoginFlow`）。层层加 `onAddAccount` / `addingAccount` 要穿过三个签名，
 * 其中 `MainScreen` 只是转手、完全不关心这件事。这里用与 `ThemeRuntime` / `NotifSnapshot`
 * 同一个仓储里既有的**进程内瞬时状态**模式：账号页写入、根读取。
 *
 * ## 与真状态的关系
 *
 * 它**不是**登录态，也不该被当成登录态：真正的登录态只有 `session` 键与 `LoginState`。
 * 这个标记只回答一个问题 ——「当前的登录界面，是为了新增一个号，还是因为本来就没登录」。
 * 因此它必须在两个时机收尾，否则会把用户永久留在登录页：
 *
 * - 登录**成功**（`LoginState.LoggedIn`）→ 清掉，让主界面接管；
 * - 用户从欢迎页**返回** → 由 `AccountsScreen` 的返回处理清掉，回到账号列表。
 */
object AddAccountFlow {

    private val _active: MutableState<Boolean> = mutableStateOf(false)

    /**
     * 流程开始时刻（毫秒）—— 用于**兜底过期**，见 [begin] 的「为什么需要过期」。
     */
    private var beganAt: Long = 0L

    /**
     * 兜底过期时长。
     *
     * 它只防一种残留：用户进了新增流程、中途离开账号页、之后再回来 ——
     * 此时标记不该还挂着（用户并没有要新增）。之所以用「过期」而不是「离开账号页时清」，
     * 见 [begin] 里那段踩坑记录：登录界面接管整屏时账号页会被 dispose，
     * 任何挂在它 onDispose 上的清理都会**把流程立刻取消掉**。
     */
    internal const val STALE_MS = 2 * 60 * 1000L

    /**
     * 当前是否处于「新增账号」流程（根布局据此决定登录界面要不要接管整屏）。
     *
     * **纯标记，不含时间判定**：过期只在账号页进入时由 [dropIfStale] 显式结算。
     * 早先把它写成「标记 && 未过期」，结果 getter 用真实时钟、[begin] 用可注入时钟，
     * 两者混用 —— 单测里一 begin 就立刻被判过期（测试直接红了）。时间判定留在一个地方，
     * 才好推也好测。
     */
    val active: Boolean get() = _active.value

    /**
     * 可被 Compose 观察的形态。
     *
     * `active` 是普通 getter，读它**不会**订阅变化 —— 根布局在 `if` 里读普通字段，
     * 登录成功清标记那一下不会触发重组，界面会卡在登录页。所以观察方一律用这个。
     */
    val activeState: State<Boolean> get() = _active

    /**
     * 从账号页进入新增流程。
     *
     * ## 踩坑记录：不要在账号页的 `onDispose` 里清这个标记
     *
     * 第一版在 `AccountsScreen` 上挂了 `DisposableEffect { onDispose { finish() } }`，
     * 想在「用户中途离开账号页」时收尾。结果**功能完全失效、界面毫无反应**：
     *
     * 1. `begin()` 置位 → 根 `LoginFlow` 判定「新增流程中」，于是**不再组合主界面**
     *    （这是刻意的：否则账号页会与登录界面叠两层）；
     * 2. 主界面一撤，`AccountsScreen` 立刻被 dispose → `onDispose` 马上调 `finish()`；
     * 3. 标记被清 → 又渲染主界面 → 看起来什么都没发生。
     *
     * 也就是说：**「登录界面接管整屏」与「在账号页 onDispose 里清理」在结构上互斥**，
     * 那个 onDispose 的清理对象恰恰是它自己触发的。
     * 残留改用 [STALE_MS] 兜底过期处理（账号页进入时调用 [dropIfStale]）。
     */
    fun begin(now: Long = System.currentTimeMillis()) {
        beganAt = now
        _active.value = true
    }

    private fun expired(now: Long = System.currentTimeMillis()): Boolean =
        _active.value && beganAt > 0L && now - beganAt > STALE_MS

    /**
     * 账号页进入时调用：残留过一次「进了新增流程又中途离开」的标记就丢掉。
     *
     * @return true 表示这次确实丢掉了一个残留标记（调用方可据此记一行日志）
     */
    fun dropIfStale(now: Long = System.currentTimeMillis()): Boolean {
        if (!expired(now)) return false
        _active.value = false
        return true
    }

    /**
     * 结束新增流程。
     *
     * 幂等：登录成功与用户返回可能几乎同时发生（例如授权完成后立刻按返回），
     * 重复调用不该有任何副作用。
     *
     * ⚠️ 只有**用户真的走完或退出**新增流程时才调它。不要在账号页的 `onDispose` 里调 ——
     * 见 [begin] 的踩坑记录，那会把流程在自己开始的同一刻取消掉。
     */
    fun finish() {
        _active.value = false
        beganAt = 0L
    }

    /** 仅供单测：把状态复位（进程内单例不该在测试之间串味）。 */
    internal fun resetForTest() {
        _active.value = false
        beganAt = 0L
    }
}
