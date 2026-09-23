package com.branchbase.core

import android.content.Context
import com.branchbase.ui.log.Logger
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 账号健康检查。
 *
 * 判定走 `GET /user`：401 → 令牌失效；403 且响应体含 `suspended` → 账号被封禁；
 * 403/429 且含 `rate limit` → 触发限流；网络异常 → 无法连接（保留上一次结果）。
 * **401/403 还要求响应「像 GitHub 的」**（见 `AccountStore.looksLikeGitHub`）：代理 / 门户 /
 * 加速网关回话时判「无法连接」，不能写成「令牌已失效」。
 *
 * ## 三条防误判（1.0.61 补，起因是真机日志）
 *
 * 真机出现过：同一次会话里 `/user` 报「令牌已失效」，而 `/user/repos`、`/notifications`、
 * GraphQL 全是 200 —— 令牌显然是好的。误判的代价很具体：用户去重新登录（没用），
 * 而结论会**粘住整场会话**（重探只在手动点或网络跃迁时发生）。所以：
 *
 * 1. **证据入日志**：记下 HTTP 码与响应体前若干字符。此前只记「结论」，
 *    出了误判只能靠猜 —— 这正是这条 bug 拖了这么久的原因；
 * 2. **复核一次**：401/403 类的结论必须换一个端点（`/user/repos?per_page=1`）确认；
 *    只有两个端点都失败才定性。`/user` 被代理特判、或链路上有中间人时，单端点失败是可能的；
 * 3. **交叉验证**：同一个 token 最近成功过（[ApiEvidence]，任何一次成功的 API 调用都会记一笔），
 *    就不可能是「令牌失效」，降级为「无法连接」—— 诚实地说「这次探测没成」，
 *    而不是给用户一个会让人白折腾的结论。
 *
 * ## 1.0.76 补：把「为什么失效」也查出来（第二次真机日志）
 *
 * 又一份日志：`/user` 与复核端点**都**回 401 且响应体是**真 GitHub 形状**
 * （`{"message":"Bad credentials", "documentation_url":…, "status":"401"}`）——
 * 三条防线全部通过，结论「令牌已失效」**是对的**。但日志到此为止，仍然答不出
 * 用户最关心的那个问题：**这枚 token 到底是过期了、还是被吊销了？**
 *
 * 这次能答了，因为 GitHub 在**每一次**认证请求（包括 401）上都会回
 * `GitHub-Authentication-Token-Expiration` 头 —— 而 [RustBridge.getJson] 一路只保留
 * 「状态码 + 响应体」，**响应头全被丢掉**。所以加一条独立的轻量探针 [probe]：
 *
 * - `HEAD https://host/` 带同一个 Authorization 头（HEAD `/` 不计入速率限制），
 *   读 `GitHub-Authentication-Token-Expiration`；
 * - 探针**不参与判定** —— 判定仍然只由 [AccountStore.statusFromResponse] 说了算。
 *   它的唯一产出是一句写进日志的白话（[expiryNote]），解释「为什么」。
 *
 * 另外两处让日志下次能自证的补充：**探测的 host 与 token 指纹**（多账号 / 多 host 下
 * 才分得清探的是哪一枚），以及**探测时刻**（设备时钟偏离会让 GitHub 拒收尚未生效的
 * token，日志里没有基准时间就排不掉这一项）。
 *
 * 时机：App 启动后跑一次（见 `MainActivity`），网络跃迁时由 `NetworkWatch` 重跑，
 * 之后由用户在账号页手动触发（进入账号页只在结论**陈旧**时才自动重探，见 [isStale]）。
 */
object AccountChecks {

    /** 「重结论」：这几个状态必须复核 + 交叉验证之后才能写回。 */
    private val HEAVY_STATUSES = setOf(
        AccountStatus.INVALID,
        AccountStatus.SUSPENDED,
        AccountStatus.LIMITED,
    )

    /** 复核用的端点：与 `/user` 不同的路径，返回体小。空仓库也返回 200（`[]`）。 */
    private const val CONFIRM_PATH = "/user/repos?per_page=1"

    /**
     * 自动重探的**最小间隔**：结论比这更新时，进账号页不再自动重探。
     *
     * 为什么需要它：原来进页面无条件对 `UNKNOWN` 的账号探一次，而结论一旦是
     * 「令牌已失效」，用户每次进设置都会再看到一次同样的报错 —— 同一件事被反复说，
     * 主观上就成了「老是报失效」。结论本身应当**稳定**，只有用户点「检查」时才翻案。
     */
    const val AUTO_RECHECK_MS = 5 * 60 * 1000L

    /** 检查全部账号，返回其中状态正常的数量。 */
    suspend fun checkAll(context: Context): Int {
        var okCount = 0
        AccountStore.accounts(context).forEach { if (check(context, it) == AccountStatus.OK) okCount++ }
        return okCount
    }

    /**
     * 结论是否**陈旧**到值得自动重探（纯函数，有单测）。
     *
     * - 没查过（[AccountStatus.UNKNOWN]）→ 需要；
     * - 查过但超过 [AUTO_RECHECK_MS] → 需要；
     * - 刚查过（无论结论是好是坏）→ **不需要**，把结论稳定地显示出来即可。
     */
    fun isStale(status: AccountStatus, lastCheck: Long, now: Long = System.currentTimeMillis()): Boolean =
        status == AccountStatus.UNKNOWN || now - lastCheck >= AUTO_RECHECK_MS

    /** 检查单个账号并写回状态（lastCheck 同时刷新）。 */
    suspend fun check(context: Context, account: Account): AccountStatus {
        if (account.token.isBlank()) {
            AccountStore.updateStatus(context, account.id, AccountStatus.INVALID)
            return AccountStatus.INVALID
        }
        val result = withContext(Dispatchers.IO) {
            RustBridge.getJson(account.host, account.token, "/user")
        }
        var status = AccountStore.statusFromResponse(result)
        // ① 证据先入日志：只记结论的话，误判时无从下手。
        //    带 host / 指纹 / 探测时刻 —— 三者缺一，下次还得靠猜（见类注释 1.0.76 那段）。
        //    过期探针**只在需要解释失败时才打**：成功的账号不用为一行诊断白付一次请求。
        val expiry = if (status == AccountStatus.OK) null else probeExpiry(account)
        Logger.net(
            "GET /user (${account.login}@${account.host} tok=${fingerprint(account.token)} " +
                "@${stamp()}) → ${status.label}｜${expiry?.let { "$it ｜ " } ?: ""}" +
                "原始 ${result?.take(RAW_MAX) ?: "null"}",
            "Account",
        )

        // ② 复核：换个端点确认一次
        if (status in HEAVY_STATUSES) {
            val confirm = withContext(Dispatchers.IO) {
                RustBridge.getJson(account.host, account.token, CONFIRM_PATH)
            }
            val confirmStatus = AccountStore.statusFromResponse(confirm)
            if (confirmStatus == AccountStatus.OK) {
                Logger.net(
                    "复核 $CONFIRM_PATH 成功 → 推翻「${status.label}」（探测端点或链路上的中间人可疑）",
                    "Account",
                )
                status = AccountStatus.OK
            } else {
                Logger.net("复核 $CONFIRM_PATH → ${confirmStatus.label}（原始 ${confirm?.take(RAW_MAX) ?: "null"}）", "Account")
            }
        }

        // ③ 交叉验证：同一 token 最近成功过，就不可能是「令牌失效」
        if (status == AccountStatus.INVALID && ApiEvidence.sawSuccessRecently(account.host, account.token)) {
            Logger.net(
                "最近有用同一 token 成功的请求 → 「令牌已失效」不成立，按「无法连接」记（别让用户去重新登录）",
                "Account",
            )
            status = AccountStatus.UNREACHABLE
        }

        AccountStore.updateStatus(context, account.id, status)
        return status
    }

    // ───────────────────────── 过期探针（只解释「为什么」，不参与判定） ─────────────────────────

    /**
     * 探一次 token 的过期时间，产出一句白话；探不到就返回 null（**绝不影响判定**）。
     *
     * 单测直接测 [expiryNote] 这个纯函数，不测这里（它只有一次网络 I/O）。
     */
    suspend fun probeExpiry(account: Account): String? {
        val header = withContext(Dispatchers.IO) { probe(account.host, account.token) } ?: return null
        return expiryNote(header.value, header.httpCode, System.currentTimeMillis())
    }

    /** 过期探针的结果。 */
    internal data class ExpiryHeader(val value: String, val httpCode: Int)

    /** 探针的响应头名（GitHub 对两类 token 都用这个名字，大小写不保证）。 */
    internal const val EXPIRY_HEADER = "GitHub-Authentication-Token-Expiration"

    /**
     * `HEAD https://host/` 读过期头。
     *
     * 用 HEAD 而不是再打一次 `/user`：HEAD `/` 不计入速率限制，且我们只要头不要体。
     * 失败一律返回 null —— 这是**诊断**，坏了不该影响账号页的任何行为。
     */
    private fun probe(host: String, token: String): ExpiryHeader? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://$host/") as HttpURLConnection).apply {
                requestMethod = "HEAD"
                setRequestProperty("Authorization", "token $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Branchbase/0.1")
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            val raw = conn.headerFields
                ?.entries
                ?.firstOrNull { (k, _) -> k?.equals(EXPIRY_HEADER, ignoreCase = true) == true }
                ?.value
                ?.firstOrNull()
            if (raw.isNullOrBlank()) null else ExpiryHeader(raw, code)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: ClassCastException) {
            // 不是 http(s) 端点（用户把 host 填成了别的协议）
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 由过期头 + HTTP 码产出一句白话结论（**纯函数，有单测**）。
     *
     * 三种形态各自对应一个不同的用户动作，所以必须分开说：
     * - 头里是**过去**的时间 → 真过期了，去重新生成 token（这是唯一需要用户动手的一种）；
     * - 头里是**将来**的时间 → token 有到期日但还没到，401 是别的原因；
     * - 头在但解析不出 / 401 且没头 → 说清楚「查不到」，不要编。
     */
    fun expiryNote(expiry: String?, httpCode: Int, nowMs: Long): String? {
        val parsed = parseExpiryMs(expiry)
        return when {
            expiry.isNullOrBlank() -> if (httpCode == 401) {
                "GitHub 未回过期头（令牌可能已被吊销，或不是该 host 签发的）"
            } else {
                null
            }
            parsed == null -> "过期头无法解析：${expiry.take(60)}"
            parsed <= nowMs -> "令牌已过期（${expiry.take(60)}，请在 GitHub 重新生成）"
            else -> {
                val days = (parsed - nowMs) / 86_400_000L
                "令牌有效期至 ${expiry.take(60)}（还剩 $days 天）"
            }
        }
    }

    /**
     * 解析 GitHub 的过期时间戳。
     *
     * 实测形态是 `2026-09-30 07:12:34 UTC`（**空格分隔、无 `T`**，与 ISO8601 不同），
     * 因此先按这个格式来，再退回 ISO8601（GitHub Enterprise 上见过带 `T` 的变体）。
     */
    fun parseExpiryMs(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        EXPIRY_FORMATTERS.forEach { fmt ->
            val ms = runCatching {
                java.time.LocalDateTime.parse(s, fmt).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
            if (ms != null) return ms
        }
        return null
    }

    /**
     * 两种形态（都是 UTC）：
     * - `2026-09-30 07:12:34 UTC` —— GitHub 实际回的形态（空格分隔，**没有 `T`**）；
     * - `2026-09-30T07:12:34Z` —— ISO8601 变体，GitHub Enterprise 上见过。
     *
     * 用 `java.time` 而不是 `SimpleDateFormat`：后者**不是线程安全的**，
     * 而这两个格式化器是 object 级常量，启动检查与网络跃迁可能并发命中。
     */
    private val EXPIRY_FORMATTERS = listOf(
        // 带时区段（`… UTC` / `… GMT`）
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz", Locale.US),
        // 没有时区段（GitHub 有的端点上就是裸的 `2026-09-30 07:12:34`）—— 按 UTC 解释
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
    )

    /**
     * token 指纹：`tok=` 后面跟 8 位 sha256 前缀。
     *
     * 用途是**区分**而不是**识别**（多账号 / 多 host 时，「探的是哪一枚」要一眼看得出），
     * 而日志会被导出、贴进 issue。所以只记哈希前 8 位，绝不记原文。
     */
    fun fingerprint(token: String): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
    }.getOrDefault("?")

    /** 探针超时：它是诊断，不能拖着账号页。 */
    private const val PROBE_TIMEOUT_MS = 4000

    /** 原始响应进日志的截断长度。160 会把 GitHub 错误体里的 `request_id` 切掉，提到 400。 */
    private const val RAW_MAX = 400
}

/**
 * 日志用的时刻：`2026-09-23 19:45:28`（本地时区），人肉排查时比毫秒时间戳好读。
 *
 * 为什么要把探测时刻写进日志：**设备时钟偏离**是「GitHub 拒收一枚刚生成的 token」的已知原因
 * （服务端认为生效时间还没到，回 401 Bad credentials）。日志里没有基准时间，这一项就排不掉。
 */
internal fun stamp(nowMs: Long = System.currentTimeMillis()): String =
    java.time.Instant.ofEpochMilli(nowMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
