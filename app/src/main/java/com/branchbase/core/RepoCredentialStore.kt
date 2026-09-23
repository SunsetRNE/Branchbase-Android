package com.branchbase.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * **仓库级凭据**：给「当前账号打不开的私有仓库」单独配一条令牌。
 *
 * ## 规则（产品口径，改之前先确认）
 *
 * 1. **账号优先**：当前账号能打开这个仓库时，一律用账号的会话 —— 仓库凭据只是**回退**，
 *    不参与「能访问时用谁」的竞争；
 * 2. **回退后读写都用它**：一旦回退到仓库凭据，这个仓库页里的**读与写**（提交 / 开 PR / 合并）
 *    都走这条令牌 —— 也就是说，**这个仓库里的动作身份可能与界面显示的账号不同**，
 *    所以使用中必须有可见提示（见 `RepositoryScreen` 的横幅）；
 * 3. 入口只在**令牌登录模式**（`AuthKind.PAT`）下出现在设置里（见设置页的登记条件）。
 *
 * ## 为什么单独一个 prefs 文件
 *
 * 令牌是长期凭据，落盘口径要能单独收紧：现在与其它 store 一样是明文 `MODE_PRIVATE`，
 * 但放在独立的 `repo_credentials` 文件里，将来加 Keystore 加密或整文件排除备份时
 * **只动这一处**（`res/xml/backup_rules.xml` 已按文件排除，见该文件注释）。
 *
 * ## 铁律
 *
 * **令牌永不进日志**：本文件不调用任何 Logger；UI 侧只显示 `@login` 与仓库名。
 */
data class RepoCredential(
    val host: String,
    val owner: String,
    val repo: String,
    /** 令牌所属身份（`GET /user` 探测得到），用于显示与审计 —— **不是**当前账号。 */
    val login: String,
    val token: String,
    val addedAt: Long,
    val lastUsedAt: Long,
) {
    /** 展示用：`owner/repo`。 */
    val slug: String get() = "$owner/$repo"
}

object RepoCredentialStore {

    private const val PREFS = "repo_credentials"
    private const val KEY_ITEMS = "items"

    /** 全部仓库凭据（按添加时间正序）。 */
    fun all(context: Context): List<RepoCredential> =
        parseRepoCredentials(prefs(context).getString(KEY_ITEMS, null)).sortedBy { it.addedAt }

    /** 找这个仓库的凭据（host 大小写不敏感，owner/repo 按 GitHub 规则大小写不敏感）。 */
    fun find(context: Context, host: String, owner: String, repo: String): RepoCredential? =
        all(context).firstOrNull { it.sameTarget(host, owner, repo) }

    fun has(context: Context, host: String, owner: String, repo: String): Boolean =
        find(context, host, owner, repo) != null

    /**
     * 新增或**就地替换**这个仓库的凭据（同一 (host, owner, repo) 只留一条）。
     *
     * 返回落库后的记录；`token` 为空时不写、返回 null。
     */
    fun save(
        context: Context,
        host: String,
        owner: String,
        repo: String,
        token: String,
        login: String,
        now: Long = System.currentTimeMillis(),
    ): RepoCredential? {
        val item = newRepoCredential(all(context), host, owner, repo, token, login, now) ?: return null
        write(context, upsertRepoCredential(all(context), item))
        return item
    }

    /** 删除这个仓库的凭据；返回是否真的删掉了。 */
    fun remove(context: Context, host: String, owner: String, repo: String): Boolean {
        val items = all(context)
        val next = removeRepoCredential(items, host, owner, repo)
        if (next.size == items.size) return false
        write(context, next)
        return true
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ITEMS).apply()
    }

    /** 记一次使用（用于「上次使用」排序与排查）。 */
    fun touch(context: Context, host: String, owner: String, repo: String, now: Long = System.currentTimeMillis()) {
        val items = all(context)
        val idx = items.indexOfFirst { it.sameTarget(host, owner, repo) }
        if (idx < 0) return
        write(context, items.toMutableList().also { it[idx] = it[idx].copy(lastUsedAt = now) })
    }

    // ── 内部 ──

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun write(context: Context, items: List<RepoCredential>) {
        prefs(context).edit().putString(KEY_ITEMS, encodeRepoCredentials(items)).apply()
    }

    private fun toJson(c: RepoCredential): JSONObject = JSONObject().apply {
        put("host", c.host)
        put("owner", c.owner)
        put("repo", c.repo)
        put("login", c.login)
        put("token", c.token)
        put("addedAt", c.addedAt)
        put("lastUsedAt", c.lastUsedAt)
    }

    private fun fromJson(o: JSONObject) = RepoCredential(
        host = o.optString("host", "github.com"),
        owner = o.optString("owner"),
        repo = o.optString("repo"),
        login = o.optString("login"),
        token = o.optString("token"),
        addedAt = o.optLong("addedAt"),
        lastUsedAt = o.optLong("lastUsedAt"),
    )
}

/** GitHub 的大小写规则：host / owner / repo 比较都不区分大小写。 */
internal fun RepoCredential.sameTarget(host: String, owner: String, repo: String): Boolean =
    this.host.equals(host.ifBlank { "github.com" }, ignoreCase = true) &&
        this.owner.equals(owner, ignoreCase = true) &&
        this.repo.equals(repo, ignoreCase = true)

// ── 纯逻辑（可单测：本仓库的单测不引 Context，所以把编解码与增删规则放在这里）──

/** 反序列化：坏 JSON / 缺字段 / 空令牌一律**跳过**（宁可少一条，也不要一条用不了的凭据）。 */
internal fun parseRepoCredentials(raw: String?): List<RepoCredential> {
    if (raw.isNullOrBlank()) return emptyList()
    val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    val out = ArrayList<RepoCredential>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val item = RepoCredential(
            host = o.optString("host", "github.com"),
            owner = o.optString("owner"),
            repo = o.optString("repo"),
            login = o.optString("login"),
            token = o.optString("token"),
            addedAt = o.optLong("addedAt"),
            lastUsedAt = o.optLong("lastUsedAt"),
        )
        if (item.token.isBlank() || item.owner.isBlank() || item.repo.isBlank()) continue
        out += item
    }
    return out
}

internal fun encodeRepoCredentials(items: List<RepoCredential>): String {
    val arr = JSONArray()
    items.forEach { c ->
        arr.put(
            JSONObject().apply {
                put("host", c.host)
                put("owner", c.owner)
                put("repo", c.repo)
                put("login", c.login)
                put("token", c.token)
                put("addedAt", c.addedAt)
                put("lastUsedAt", c.lastUsedAt)
            },
        )
    }
    return arr.toString()
}

/**
 * 造一条新记录：**同一 (host, owner, repo) 只留一条** —— 替换时沿用原来的 `addedAt`
 * （「什么时候加的这个仓库」不该因为换令牌而被改写）。参数不合法返回 null。
 */
internal fun newRepoCredential(
    existing: List<RepoCredential>,
    host: String,
    owner: String,
    repo: String,
    token: String,
    login: String,
    now: Long,
): RepoCredential? {
    if (token.isBlank() || owner.isBlank() || repo.isBlank()) return null
    val h = host.ifBlank { "github.com" }
    val old = existing.firstOrNull { it.sameTarget(h, owner, repo) }
    return RepoCredential(
        host = h,
        owner = owner,
        repo = repo,
        login = login,
        token = token,
        addedAt = old?.addedAt ?: now,
        lastUsedAt = now,
    )
}

/** 插入或就地替换（保持其余顺序不变）。 */
internal fun upsertRepoCredential(items: List<RepoCredential>, item: RepoCredential): List<RepoCredential> {
    val out = items.toMutableList()
    val idx = out.indexOfFirst { it.sameTarget(item.host, item.owner, item.repo) }
    if (idx >= 0) out[idx] = item else out += item
    return out
}

/** 删除（大小写不敏感地匹配目标）；目标不存在时原样返回。 */
internal fun removeRepoCredential(
    items: List<RepoCredential>,
    host: String,
    owner: String,
    repo: String,
): List<RepoCredential> = items.filterNot { it.sameTarget(host, owner, repo) }
