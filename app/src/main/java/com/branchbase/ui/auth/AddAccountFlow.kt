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

    /** 当前是否处于「新增账号」流程（根布局据此决定登录界面要不要接管整屏）。 */
    val active: Boolean get() = _active.value

    /**
     * 可被 Compose 观察的形态。
     *
     * `active` 是普通 getter，读它**不会**订阅变化 —— 根布局在 `if` 里读普通字段，
     * 登录成功清标记那一下不会触发重组，界面会卡在登录页。所以观察方一律用这个。
     */
    val activeState: State<Boolean> get() = _active

    /** 从账号页进入新增流程。 */
    fun begin() {
        _active.value = true
    }

    /**
     * 结束新增流程。
     *
     * 幂等：登录成功与用户返回可能几乎同时发生（例如授权完成后立刻按返回），
     * 重复调用不该有任何副作用。
     */
    fun finish() {
        _active.value = false
    }

    /** 仅供单测：把状态复位（进程内单例不该在测试之间串味）。 */
    internal fun resetForTest() {
        _active.value = false
    }
}
