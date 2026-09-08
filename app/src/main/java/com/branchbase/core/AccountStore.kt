package com.branchbase.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 账号健康状态（检查结果）。
 *
 * 判定来源见 [AccountStore.checkStatusFromResponse]：
 * - [OK] 200；[INVALID] 401；[SUSPENDED] 403 且含 suspended；
 * - [LIMITED] 403/429 且含 rate limit；[UNREACHABLE] 网络异常（保留上一次结果）。
 */
enum class AccountStatus(val label: String) {
    UNKNOWN("未检查"),
    OK("正常"),
    INVALID("令牌已失效"),
    SUSPENDED("账号已被封禁"),
    LIMITED("触发限流"),
    UNREACHABLE("无法连接"),
}

/** 认证方式。 */
enum class AuthKind(val label: String) {
    OAUTH("OAuth"),
    PAT("PAT"),
    UNKNOWN("未知"),
}

/**
 * 本地账号。
 *
 * @param id 稳定标识（新增时生成，删除后不复用）
 * @param login GitHub 登录名 —— 本地仓库目录与任务记录都按它隔离
 * @param session 原始会话 JSON（OAuth 的 token 对象，或 PAT 包装），保持与既有代码兼容
 */
data class Account(
    val id: String,
    val login: String,
    val host: String = "github.com",
    val avatar: String? = null,
    val auth: AuthKind = AuthKind.OAUTH,
    val session: String = "",
    val addedAt: Long = 0L,
    val lastCheck: Long = 0L,
    val status: AccountStatus = AccountStatus.UNKNOWN,
) {
    /** 会话里的 access token（供 API 调用）。 */
    val token: String get() = AccountStore.accessTokenOf(session)
}

/**
 * 多账号存储（SharedPreferences `branchbase`）。
 *
 * 数据布局：
 * - `accounts`：JSON 数组，元素见 [Account]
 * - `current_account`：当前账号 id
 *
 * 兼容迁移：首次读取时若 `accounts` 不存在而旧的单账号 `session` 还在，
 * 自动把它迁移成第一条账号（原 `session` 键保留不动，避免旧代码路径炸）。
 *
 * 删除语义：**只移除账号记录**。该账号的本地仓库（`repos/{login}/…`）与任务记录
 * 一律保留 —— 重新添加同 login 的账号后自动重新可见，其他账号始终看不到。
 */
object AccountStore {

    private const val PREFS = "branchbase"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_CURRENT = "current_account"
    private const val LEGACY_KEY_SESSION = "session"

    /** 未登录时的占位账号名，用于让任务/仓库在登录前也能落盘。 */
    const val GUEST_LOGIN = ""

    // ───────────────────────── 读取 ─────────────────────────

    /** 全部账号（按添加时间正序）。 */
    fun accounts(context: Context): List<Account> {
        val prefs = prefs(context)
        migrateIfNeeded(context)
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Account>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += fromJson(o)
        }
        return out.sortedBy { it.addedAt }
    }

    /** 当前账号（未登录返回 null）。 */
    fun current(context: Context): Account? {
        val all = accounts(context)
        if (all.isEmpty()) return null
        val id = prefs(context).getString(KEY_CURRENT, null)
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    /**
     * 当前账号的 login —— 仓库目录与任务隔离键。
     *
     * 未登录时返回 [GUEST_LOGIN]（空串），调用方无需判空即可拼路径。
     */
    fun currentLogin(context: Context): String = current(context)?.login ?: GUEST_LOGIN

    /** 当前账号的 access token（未登录返回空串）。 */
    fun currentToken(context: Context): String = current(context)?.token ?: ""

    // ───────────────────────── 写入 ─────────────────────────

    /**
     * 新增账号（已存在同 host + login 则更新其 session 并返回该账号）。
     *
     * @return 新账号（写入失败返回 null）
     */
    fun add(
        context: Context,
        login: String,
        session: String,
        host: String = "github.com",
        avatar: String? = null,
        auth: AuthKind = AuthKind.OAUTH,
        makeCurrent: Boolean = true,
    ): Account? {
        if (login.isBlank()) return null
        val all = accounts(context).toMutableList()
        val now = System.currentTimeMillis()
        val exist = all.indexOfFirst { it.login.equals(login, ignoreCase = true) && it.host == host }
        val account: Account
        if (exist >= 0) {
            account = all[exist].copy(
                session = session,
                avatar = avatar ?: all[exist].avatar,
                auth = auth,
                lastCheck = 0L,
                status = AccountStatus.UNKNOWN,
            )
            all[exist] = account
        } else {
            account = Account(
                id = "acc-" + now.toString(36) + "-" + (0..999).random(),
                login = login,
                host = host,
                avatar = avatar,
                auth = auth,
                session = session,
                addedAt = now,
            )
            all += account
        }
        save(context, all)
        if (makeCurrent || prefs(context).getString(KEY_CURRENT, null) == null) {
            prefs(context).edit().putString(KEY_CURRENT, account.id).apply()
            syncLegacySession(context, account)
        }
        return account
    }

    /** 删除账号记录（保留其本地仓库与任务）。 */
    fun remove(context: Context, id: String): Boolean {
        val all = accounts(context).toMutableList()
        val target = all.firstOrNull { it.id == id } ?: return false
        all.remove(target)
        save(context, all)
        if (prefs(context).getString(KEY_CURRENT, null) == target.id) {
            prefs(context).edit().putString(KEY_CURRENT, all.firstOrNull()?.id ?: "").apply()
        }
        return true
    }

    /** 切换当前账号（同时同步旧 `session` 键，供既有登录态读取路径复用）。 */
    fun switchTo(context: Context, id: String): Boolean {
        val all = accounts(context)
        val target = all.firstOrNull { it.id == id } ?: return false
        prefs(context).edit().putString(KEY_CURRENT, id).apply()
        syncLegacySession(context, target)
        return true
    }

    /**
     * 把账号会话同步到旧的单账号键 `session`。
     *
     * 登录态（`LoginViewModel.init`）仍从该键恢复，因此切换账号后
     * 只需重建 Activity 即可让全 App 切到新账号，无需逐层传递 session。
     */
    private fun syncLegacySession(context: Context, account: Account) {
        if (account.session.isNotBlank()) {
            prefs(context).edit().putString(LEGACY_KEY_SESSION, account.session).apply()
        }
    }

    /** 更新账号的检查结果。 */
    fun updateStatus(context: Context, id: String, status: AccountStatus, at: Long = System.currentTimeMillis()) {
        val all = accounts(context).toMutableList()
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return
        all[i] = all[i].copy(status = status, lastCheck = at)
        save(context, all)
    }

    /** 更新账号的会话（令牌续期后调用）。 */
    fun updateSession(context: Context, id: String, session: String) {
        val all = accounts(context).toMutableList()
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return
        all[i] = all[i].copy(session = session)
        save(context, all)
    }

    // ───────────────────────── 会话解析 ─────────────────────────

    /** 从会话 JSON 里取 access token（结构：`{"token":{"access_token":"…"}}`）。 */
    fun accessTokenOf(session: String): String = runCatching {
        val root = JSONObject(session)
        root.optJSONObject("token")?.optString("access_token")
            ?: root.optString("access_token")
    }.getOrNull().orEmpty()

    /** 从会话 JSON 里取 refresh token（PAT 通常没有）。 */
    fun refreshTokenOf(session: String): String = runCatching {
        JSONObject(session).optJSONObject("token")?.optString("refresh_token")
    }.getOrNull().orEmpty()

    /** 从会话 JSON 里尽力取 login（部分导入路径会写入 user 信息）。 */
    fun loginOf(session: String): String? = runCatching {
        val root = JSONObject(session)
        root.optJSONObject("user")?.optString("login")?.takeIf { it.isNotBlank() }
            ?: root.optString("login").takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 依据一次 `/user` 探测结果判定账号状态。
     *
     * @param httpCode 0 表示网络异常（连接失败/超时）
     * @param body 响应体（用于识别 suspended / rate limit 文案）
     */
    fun checkStatusFromResponse(httpCode: Int, body: String): AccountStatus = when {
        httpCode == 0 -> AccountStatus.UNREACHABLE
        httpCode in 200..299 -> AccountStatus.OK
        httpCode == 401 -> AccountStatus.INVALID
        body.contains("suspended", ignoreCase = true) -> AccountStatus.SUSPENDED
        body.contains("rate limit", ignoreCase = true) -> AccountStatus.LIMITED
        httpCode == 403 -> AccountStatus.INVALID
        httpCode == 429 -> AccountStatus.LIMITED
        else -> AccountStatus.UNREACHABLE
    }

    /**
     * 从 [RustBridge.getJson] 的返回判定状态。
     *
     * 引擎成功时返回原始 JSON；失败时返回形如
     * `ERROR: HTTP 403 Forbidden: {"message":"…"}`（见 core/src/api/client.rs），
     * 状态码与响应体都在同一个串里，因此直接解析即可。
     */
    fun statusFromResponse(result: String?): AccountStatus {
        if (result == null) return AccountStatus.UNREACHABLE
        if (!result.startsWith("ERROR:")) return AccountStatus.OK
        val code = HTTP_CODE.find(result)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return checkStatusFromResponse(code, result)
    }

    /** 从 `/user` 的成功响应里取 login（新增账号时用于补全登录名）。 */
    fun loginFromUserResponse(result: String?): String? = runCatching {
        if (result == null || result.startsWith("ERROR:")) null
        else JSONObject(result).optString("login").takeIf { it.isNotBlank() }
    }.getOrNull()

    private val HTTP_CODE = Regex("HTTP (\\d{3})")

    // ───────────────────────── 内部 ─────────────────────────

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(context: Context, list: List<Account>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs(context).edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    private fun toJson(a: Account): JSONObject = JSONObject().apply {
        put("id", a.id)
        put("login", a.login)
        put("host", a.host)
        put("avatar", a.avatar ?: JSONObject.NULL)
        put("auth", a.auth.name)
        put("session", a.session)
        put("addedAt", a.addedAt)
        put("lastCheck", a.lastCheck)
        put("status", a.status.name)
    }

    private fun fromJson(o: JSONObject): Account = Account(
        id = o.optString("id"),
        login = o.optString("login"),
        host = o.optString("host", "github.com"),
        avatar = o.optString("avatar").takeIf { it.isNotBlank() && it != "null" },
        auth = runCatching { AuthKind.valueOf(o.optString("auth")) }.getOrDefault(AuthKind.UNKNOWN),
        session = o.optString("session"),
        addedAt = o.optLong("addedAt"),
        lastCheck = o.optLong("lastCheck"),
        status = runCatching { AccountStatus.valueOf(o.optString("status")) }.getOrDefault(AccountStatus.UNKNOWN),
    )

    /**
     * 单账号 → 多账号的一次性迁移。
     *
     * 旧版本只存 `session`；这里把它变成第一条账号，login 优先取会话里
     * 已写入的 user.login，取不到就用 `legacy`（后续登录成功时会自动纠正）。
     */
    private fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.contains(KEY_ACCOUNTS)) return
        val legacy = p.getString(LEGACY_KEY_SESSION, null)
        if (legacy.isNullOrBlank()) {
            p.edit().putString(KEY_ACCOUNTS, "[]").apply()
            return
        }
        val login = loginOf(legacy) ?: "legacy"
        val acc = Account(
            id = "legacy-" + login,
            login = login,
            session = legacy,
            addedAt = System.currentTimeMillis(),
            auth = if (refreshTokenOf(legacy).isBlank()) AuthKind.PAT else AuthKind.OAUTH,
        )
        save(context, listOf(acc))
        p.edit().putString(KEY_CURRENT, acc.id).apply()
    }
}
