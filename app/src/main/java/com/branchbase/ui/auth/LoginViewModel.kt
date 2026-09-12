package com.branchbase.ui.auth

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.branchbase.BuildConfig
import com.branchbase.core.AccountStore
import com.branchbase.core.AuthKind
import com.branchbase.core.AvatarCache
import com.branchbase.core.RustBridge
import com.branchbase.ui.navigation.PageLevel
import com.branchbase.ui.log.LogCategory
import com.branchbase.ui.log.Logger
import com.branchbase.ui.task.TaskStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 登录状态机。
 *
 * 状态流转（Idle → Authorizing → ExchangingToken → NeedTwoFactor → LoggedIn / Error）：
 * ```
 * Idle ──startOAuth──▶ Authorizing ──回调code──▶ ExchangingToken
 *                                                 ├──成功──▶ LoggedIn
 *                                                 └──失败──▶ Error
 * LoggedIn ──logout──▶ Idle
 * ```
 *
 * 说明：原「游客浏览（Guest）」只有占位页（`待接入主界面`），已随占位页一并移除。
 *
 * 会话持久化：sessionJson 存入 SharedPreferences，启动时自动恢复登录态。
 */

/**
 * 登录状态。
 *
 * 实现 [PageLevel] 是为了让登录流程内部的切换也有方向：欢迎页(0) → 模式介绍页(1) →
 * 授权中 / 密钥填写(2) → 进入主界面(3)。前进从右滑入、返回向右滑出，
 * 与 App 其它页面同一套动效规则（见 `ui/navigation/PageTransitions.kt`）。
 */
sealed interface LoginState : PageLevel {
    /** 初始状态（欢迎页：两个入口 —— 授权登录 / 密钥登录） */
    data object Idle : LoginState {
        override val depth: Int get() = 0
    }

    /** 「授权登录」流程要点介绍页（含口令验证说明 + 渲染动画） */
    data object OAuthIntro : LoginState {
        override val depth: Int get() = 1
    }

    /** 「密钥登录」流程要点介绍页（含网页端生成密钥 / 勾选权限说明 + 渲染动画） */
    data object KeyIntro : LoginState {
        override val depth: Int get() = 1
    }

    /**
     * 填写访问密钥（**纯页面身份**）。
     *
     * 校验中的转圈与失败原因**不放在这里**，而是 [LoginViewModel.keyBusy] / [LoginViewModel.keyError]：
     * 这一页的「请求态」如果进了页面状态，提交时状态值一变就会被当成换页 ——
     * 既会多播一次切换动画，又会重建内容、把用户刚粘贴的密钥丢掉。
     */
    data object KeyInput : LoginState {
        override val depth: Int get() = 2
    }

    /** 正在授权（跳转 GitHub 授权页） */
    data class Authorizing(val authorizeUrl: String, val verifier: String) : LoginState {
        override val depth: Int get() = 2
    }

    /** 正在交换 token */
    data object ExchangingToken : LoginState {
        override val depth: Int get() = 2
    }

    /** 需要双因子验证 */
    data object NeedTwoFactor : LoginState {
        override val depth: Int get() = 2
    }

    /** 已登录（sessionJson 为 Rust 返回的会话 JSON） */
    data class LoggedIn(val sessionJson: String) : LoginState {
        override val depth: Int get() = 3
    }

    /** 出错 */
    data class Error(val message: String) : LoginState {
        override val depth: Int get() = 2
    }
}

/** OAuth 应用配置（clientId / redirectUri 从 local.properties 经 BuildConfig 注入） */
data class OAuthCredentials(
    val clientId: String = "Ov23lizD94xBmHkhyyG6",
    val redirectUri: String = BuildConfig.GITHUB_REDIRECT_URI,
    val host: String = "github.com",
    val scopes: List<String> = listOf("repo", "read:user", "read:org", "notifications"),
)

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("branchbase", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    /** 密钥校验中（按钮转圈 / 输入框锁定）。 */
    private val _keyBusy = MutableStateFlow(false)
    val keyBusy: StateFlow<Boolean> = _keyBusy.asStateFlow()

    /** 密钥校验失败原因（null = 无错误）。 */
    private val _keyError = MutableStateFlow<String?>(null)
    val keyError: StateFlow<String?> = _keyError.asStateFlow()

    private val credentials = OAuthCredentials()

    companion object {
        const val KEY_SESSION = "session"

        /** PKCE verifier（跨 Activity 重建保留，供深链回调时交换 token） */
        @Volatile
        var pkceVerifier: String? = null
    }

    init {
        // 启动时恢复已保存的会话（登录持久化）
        val saved = prefs.getString(KEY_SESSION, null)
        if (!saved.isNullOrBlank()) {
            _state.value = LoginState.LoggedIn(saved)
            // 补全会话里的 user：老会话或 OAuth 直登时缺失 → 首页/个人页/动态页会退化为占位
            persistAccount(saved)
            // 静默续期：用 refresh token 刷新 access token（滚动续期）
            refreshSession(saved)
        }
    }

    /** 用 refresh token 刷新 access token，更新持久化的会话（失败则保持原会话） */
    private fun refreshSession(sessionJson: String) {
        viewModelScope.launch {
            // 取 prefs 里的最新会话：persistAccount 可能刚补全 user，用入参旧值会把它覆盖掉
            val current = prefs.getString(KEY_SESSION, null)?.takeIf { it.isNotBlank() } ?: sessionJson
            val refreshToken = runCatching {
                JSONObject(current).getJSONObject("token").optString("refresh_token")
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@launch

            val newTokenJson = RustBridge.refreshToken(
                clientId = credentials.clientId,
                clientSecret = BuildConfig.BRANCHBASE_CLIENT_SECRET,
                host = credentials.host,
                refreshToken = refreshToken
            )
            if (newTokenJson.startsWith("ERROR:") || newTokenJson.isBlank()) {
                return@launch // 刷新失败，保持原会话
            }

            val newSession = runCatching {
                val session = JSONObject(current)
                session.put("token", JSONObject(newTokenJson))
                session.toString()
            }.getOrNull() ?: return@launch

            prefs.edit().putString(KEY_SESSION, newSession).apply()
            _state.value = LoginState.LoggedIn(newSession)
            // 同步到多账号表：否则 AccountChecks 仍拿旧 token 探测，会误报「令牌已失效」
            val app = getApplication<Application>()
            AccountStore.current(app)?.let { AccountStore.updateSession(app, it.id, newSession) }
        }
    }

    // ───────────────────────── 两种登录模式的入口 ─────────────────────────

    /** 欢迎页「授权登录」→ 流程要点介绍页 */
    fun showOAuthIntro() {
        _state.value = LoginState.OAuthIntro
    }

    /** 欢迎页「密钥登录」→ 流程要点介绍页 */
    fun showKeyIntro() {
        _state.value = LoginState.KeyIntro
    }

    /** 介绍页「填写密钥」→ 密钥输入页（进入时清掉上一轮的失败提示） */
    fun showKeyInput() {
        _keyError.value = null
        _keyBusy.value = false
        _state.value = LoginState.KeyInput
    }

    /**
     * 密钥（PAT）登录。
     *
     * ## 为什么用 `GET /user` 校验而不是「填了就信」
     *
     * 密钥可能：过期、被撤销、权限勾少了（比如没勾 `read:user`）。这三种都会让 App 登录后
     * 一路报错，用户却以为是 App 的问题。这里用一次 `/user` 把不可用的情况**挡在登录前**，
     * 并把原因（无效 / 权限不足）直接显示在输入页上。
     *
     * 成功后会组装一份与 OAuth **同构**的会话 JSON（`host` / `token.access_token` / `user`），
     * 这样 `sessionInfo()`、`AccountStore.accessTokenOf()`、账号健康检查等既有链路全都不用改。
     */
    fun loginWithKey(rawToken: String) {
        val token = rawToken.trim()
        if (token.isBlank()) {
            _keyError.value = "请先粘贴访问密钥"
            return
        }
        _keyError.value = null
        _keyBusy.value = true

        viewModelScope.launch {
            val host = credentials.host
            val userJson = RustBridge.getCurrentUser(host, token)
            val user = userJson
                ?.takeIf { !it.startsWith("ERROR:") }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val login = user?.optString("login").orEmpty()
            _keyBusy.value = false

            // 用户在等待期间按了返回：结果直接丢弃，不要把他"拉回"这一页
            if (_state.value !is LoginState.KeyInput) return@launch

            if (user == null || login.isBlank() || login == "null") {
                Logger.ui("密钥校验未通过（无效或权限不足）", "密钥登录")
                _keyError.value = "密钥无效或权限不足。请确认已勾选 repo / read:user，且密钥未过期。"
                return@launch
            }

            val session = runCatching { buildKeySession(host, token, user) }.getOrNull()
            if (session == null) {
                _keyError.value = "会话写入失败，请重试"
                return@launch
            }

            prefs.edit().putString(KEY_SESSION, session).apply()
            _state.value = LoginState.LoggedIn(session)
            Logger.ui("密钥登录成功：$login", "密钥登录")
            // 与 OAuth 同一条落库路径，只是认证方式标记为 PAT（账号管理页按它显示「PAT 令牌」）
            persistAccount(session, auth = AuthKind.PAT)
        }
    }

    /** 点击「登录」→ 生成 PKCE + 构建授权 URL → 进入授权态 */
    fun startOAuth() {
        viewModelScope.launch {
            val pkce = RustBridge.generatePkce() ?: run {
                _state.value = LoginState.Error("生成 PKCE 失败")
                return@launch
            }
            pkceVerifier = pkce.verifier
            Logger.debug(LogCategory.LOCAL_TASK, "PKCE", "生成 PKCE verifier/challenge")
            val url = RustBridge.buildAuthorizeUrl(
                clientId = credentials.clientId,
                redirectUri = credentials.redirectUri,
                host = credentials.host,
                scopes = credentials.scopes,
                challenge = pkce.challenge
            ) ?: run {
                _state.value = LoginState.Error("构建授权 URL 失败")
                return@launch
            }
            _state.value = LoginState.Authorizing(authorizeUrl = url, verifier = pkce.verifier)
        }
    }

    /** 消费深链回调的授权码（由 MainActivity 解析后调用） */
    fun consumeDeepLinkCode(code: String?) {
        val verifier = pkceVerifier
        if (code == null || verifier == null) {
            return
        }
        onAuthCodeReceived(code, verifier)
    }

    /** 授权回调拿到 code → 交换 token */
    fun onAuthCodeReceived(code: String, verifier: String) {
        _state.value = LoginState.ExchangingToken
        viewModelScope.launch {
            val session = RustBridge.exchangeCode(
                clientId = credentials.clientId,
                clientSecret = BuildConfig.BRANCHBASE_CLIENT_SECRET,
                redirectUri = credentials.redirectUri,
                host = credentials.host,
                code = code,
                verifier = verifier
            )
            Logger.net("POST /login/oauth/access_token → ${if (session.startsWith("ERROR:")) "失败" else "200"}", "OAuth")
            when {
                session.startsWith("ERROR:") -> {
                    _state.value = LoginState.Error(session.removePrefix("ERROR:"))
                }
                session.isBlank() -> {
                    _state.value = LoginState.Error("token 交换失败")
                }
                else -> {
                    // 登录成功：持久化会话，并登记到多账号表（设为当前账号）
                    prefs.edit().putString(KEY_SESSION, session).apply()
                    _state.value = LoginState.LoggedIn(session)
                    persistAccount(session)
                }
            }
        }
    }

    /**
     * 登录成功后的账号登记 + 会话补全。
     *
     * **补全 `session.user` 是这里的关键**：OAuth 交换只返回 token，而首页 / 个人页 /
     * 仓库页 / 编辑资料页都从 `session.user` 取 login / name / avatar / 关注数 ——
     * 不补全的话这些页面全部退化成占位（个人页显示「用户」+ 0 关注，动态页请求
     * `/users/用户/received_events` 必然为空）。
     *
     * 幂等：会话里已有 `user` 时不发请求，直接登记。
     * 另外把 login 写入多账号表并设为当前账号，并认领登录前的孤儿任务。
     */
    private fun persistAccount(session: String, auth: AuthKind = AuthKind.OAUTH) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val host = runCatching { JSONObject(session).optString("host", "github.com") }.getOrDefault("github.com")
            val token = AccountStore.accessTokenOf(session)

            // 1) 补全 session.user
            var enriched = session
            val hasUser = runCatching { JSONObject(session).optJSONObject("user") }.getOrNull() != null
            if (!hasUser && token.isNotBlank()) {
                val userJson = RustBridge.getJson(host, token, "/user")
                val userObj = userJson
                    ?.takeIf { !it.startsWith("ERROR:") }
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (userObj != null) {
                    enriched = runCatching { JSONObject(session).put("user", userObj).toString() }
                        .getOrDefault(session)
                    prefs.edit().putString(KEY_SESSION, enriched).apply()
                    _state.value = LoginState.LoggedIn(enriched)
                    Logger.net("GET /user → 会话补全 user（${userObj.optString("login")}）", "OAuth")
                } else {
                    Logger.net("GET /user 失败，会话未补全 user（页面将缺用户信息）", "OAuth")
                }
            }

            // 2) 登记到多账号表
            val login = AccountStore.loginOf(enriched) ?: return@launch
            val avatar = runCatching {
                JSONObject(enriched).optJSONObject("user")?.optString("avatar_url")?.takeIf { it.isNotBlank() }
            }.getOrNull()
            AccountStore.add(app, login, enriched, host, avatar, auth = auth)

            // 2.5) 头像预热：登录即落盘，之后所有页面渲染头像都命中本地文件（零闪烁）
            if (avatar != null && !AvatarCache.has(app, login)) {
                val cached = AvatarCache.refresh(app, login, avatar)
                Logger.net("头像缓存 @$login → ${if (cached) "已落盘" else "失败"}", "Avatar")
            }

            // 3) 认领登录前的孤儿任务
            val claimed = TaskStore.claimOrphans(app)
            Logger.debug(LogCategory.LOCAL_TASK, "Account", "已登记账号 @$login（认领孤儿任务 $claimed 条）")
        }
    }

    /** 2FA 验证 */
    fun verifyTwoFactor(code: String) {
        if (RustBridge.validateTwoFactor(code)) {
            // 格式有效；实际提交 2FA 由服务端在 OAuth 流程完成
            _state.value = LoginState.ExchangingToken
        } else {
            _state.value = LoginState.Error("验证码格式错误（需 6 位数字）")
        }
    }

    /** 游客浏览 */
    /** 登出（清除持久化会话） */
    fun logout() {
        prefs.edit().remove(KEY_SESSION).apply()
        _state.value = LoginState.Idle
    }

    /** 取消当前操作，回到初始态（欢迎页） */
    fun cancel() {
        _state.value = LoginState.Idle
    }

    /**
     * 返回上一步（按状态层级，而不是一律回欢迎页）。
     *
     * - 密钥填写页 → 密钥介绍页（用户刚看过的那一页，返回应回到那里，而不是跳出整个流程）；
     * - 介绍页 / 授权中 / 换 token / 2FA / 出错 → 欢迎页；
     * - 欢迎页本身不拦截返回（由系统的默认行为退出 App）。
     */
    fun back() {
        _state.value = loginBackTarget(_state.value)
    }

    /**
     * 主界面顶层按返回：回登录首页，**保留会话**（不清 token、不删账号）。
     *
     * 与 [logout] 的区别：登出是用户在气泡菜单里的明确意图（清会话），
     * 这里只是「退出到登录页」这一层导航 —— 在登录首页再按一次返回才彻底退出 App。
     */
    fun backToWelcome() {
        _state.value = LoginState.Idle
    }

    /** 消费错误后回到初始态 */
    fun dismissError() {
        _state.value = LoginState.Idle
    }
}