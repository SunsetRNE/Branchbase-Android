package com.branchbase.ui.home

import android.content.Context
import com.branchbase.ui.settings.SettingsKeys
import org.json.JSONObject

/**
 * 首页「待处理」的**折叠记忆**（按账号分桶）。
 *
 * ## 为什么记在本地
 *
 * 「这一栏我收起来了」是纯粹的本地偏好，GitHub 没有对应概念；而且它只影响可见性，
 * 不影响取数（折叠照常刷新，见 `HomeScreen` 的 `loadTodoCounts`）。
 *
 * ## 为什么按账号
 *
 * 同一台设备可以登多个账号（`docs/specs/features-design.md` §1）。不按账号分桶的话，
 * 在 A 账号里收起一次，换到 B 账号会继承这个「收起」——用户会以为 B 没有待办。
 * 存储形状与 `FrequentRepoStore` 一致：`{"<login>": true/false}`，读一次拿到全部桶。
 *
 * ## 缺键 ≠ false
 *
 * 缺键 = 用户没手动切过 → 走状态默认（空 → 折叠，其余展开，见 `todoCollapsedByDefault`）；
 * 显式值 = 用户切过，之后以记忆为准（失败态除外）。
 */
object TodoFoldStore {

    /** 读某账号的折叠记忆；`null` = 没手动切过。 */
    fun collapsed(context: Context, login: String): Boolean? {
        val raw = SettingsKeys.prefs(context).getString(SettingsKeys.HOME_TODO_COLLAPSED, null) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return if (obj.has(login)) obj.optBoolean(login, false) else null
    }

    /**
     * 写某账号的折叠状态（覆盖式）。
     *
     * 只改这一个账号的桶，其它账号原样保留 —— 否则在 A 账号里点一下会清掉 B 的记忆。
     * `apply()`：点一下立刻落盘，主线程不做同步 I/O。
     */
    fun save(context: Context, login: String, collapsed: Boolean) {
        val prefs = SettingsKeys.prefs(context)
        val obj = runCatching {
            JSONObject(prefs.getString(SettingsKeys.HOME_TODO_COLLAPSED, null) ?: "{}")
        }.getOrNull() ?: JSONObject()
        obj.put(login, collapsed)
        prefs.edit().putString(SettingsKeys.HOME_TODO_COLLAPSED, obj.toString()).apply()
    }
}
