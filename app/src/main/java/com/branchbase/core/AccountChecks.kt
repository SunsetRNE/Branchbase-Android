package com.branchbase.core

import android.content.Context
import com.branchbase.ui.log.Logger
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
 * 1. **证据入日志**：记下 HTTP 码与响应体前 160 字符。此前只记「结论」，
 *    出了误判只能靠猜 —— 这正是这条 bug 拖了这么久的原因；
 * 2. **复核一次**：401/403 类的结论必须换一个端点（`/user/repos?per_page=1`）确认；
 *    只有两个端点都失败才定性。`/user` 被代理特判、或链路上有中间人时，单端点失败是可能的；
 * 3. **交叉验证**：同一个 token 最近成功过（[ApiEvidence]，任何一次成功的 API 调用都会记一笔），
 *    就不可能是「令牌失效」，降级为「无法连接」—— 诚实地说「这次探测没成」，
 *    而不是给用户一个会让人白折腾的结论。
 *
 * 时机：App 启动后跑一次（见 `MainActivity`），网络跃迁时由 `NetworkWatch` 重跑，
 * 之后由用户在账号页手动触发。
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

    /** 检查全部账号，返回其中状态正常的数量。 */
    suspend fun checkAll(context: Context): Int {
        var okCount = 0
        AccountStore.accounts(context).forEach { if (check(context, it) == AccountStatus.OK) okCount++ }
        return okCount
    }

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
        // ① 证据先入日志：只记结论的话，误判时无从下手
        Logger.net(
            "GET /user (${account.login}) → ${status.label}｜原始 ${result?.take(RAW_MAX) ?: "null"}",
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

    /** 原始响应进日志的截断长度：够看清 code / message / 是不是 HTML。 */
    private const val RAW_MAX = 160
}
