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
 *
 * 时机：App 启动后跑一次（见 `MainActivity`），之后由用户在账号页手动触发。
 */
object AccountChecks {

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
        val status = AccountStore.statusFromResponse(result)
        AccountStore.updateStatus(context, account.id, status)
        Logger.net("GET /user (${account.login}) → ${status.label}", "Account")
        return status
    }
}
