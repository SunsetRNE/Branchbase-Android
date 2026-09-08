package com.branchbase.ui.auth

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.branchbase.BuildConfig
import com.branchbase.core.AccountStore
import com.branchbase.core.RustBridge
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
 * 状态流转（对齐 docs/login-flow-design.md 的状态机）：
 * ```
 * Idle ──startOAuth──▶ Authorizing ──回调code──▶ ExchangingToken
 *   │                                              │
 *   ├──browseAsGuest──▶ Guest                      ├──成功──▶ LoggedIn
 *   │                                              └──失败──▶ Error
 * LoggedIn ──logout──▶ Idle
 * ```
 *
 * 会话持久化：sessionJson 存入 SharedPreferences，启动时自动恢复登录态。
 */

/** 登录状态 */
sealed interface LoginState {
    /** 初始状态 */
    data object Idle : LoginState

    /** 未登录浏览（游客） */
    data object Guest : LoginState

    /** 正在授权（跳转 GitHub 授权页） */
    data class Authorizing(val authorizeUrl: String, val verifier: String) : LoginState

    /** 正在交换 token */
    data object ExchangingToken : LoginState

    /** 需要双因子验证 */
    data object NeedTwoFactor : LoginState

    /** 已登录（sessionJson 为 Rust 返回的会话 JSON） */
    data class LoggedIn(val sessionJson: String) : LoginState

    /** 出错 */
    data class Error(val message: String) : LoginState
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
    private fun persistAccount(session: String) {
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
            AccountStore.add(app, login, enriched, host, avatar)

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
    fun browseAsGuest() {
        _state.value = LoginState.Guest
    }

    /** 登出（清除持久化会话） */
    fun logout() {
        prefs.edit().remove(KEY_SESSION).apply()
        _state.value = LoginState.Idle
    }

    /** 取消当前操作，回到初始态（返回键等场景） */
    fun cancel() {
        _state.value = LoginState.Idle
    }

    /** 消费错误后回到初始态 */
    fun dismissError() {
        _state.value = LoginState.Idle
    }
}