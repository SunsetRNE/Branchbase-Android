package com.branchbase.ui.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置项落盘键的**唯一声明处**（设计规范 `docs/specs/settings-design.md` §8.1）。
 *
 * 为什么要把 key 集中：这些键原先散在三个文件里（`commit_mode` 在 `SubPageScreens.kt`、
 * `git_proxy` 同文件、`notif_layout` 在 `NotificationModels.kt`），于是
 * 「这个键还有谁在写」只能靠全仓 grep 猜。集中之后，改一个键名只需要动这一个文件，
 * 也能被 `SettingsSpecTest` 钉住（「key 常量必须在唯一文件里声明」）。
 *
 * ## 命名规则（§8.1）
 * - `snake_case`、全小写；
 * - **不加模块前缀** —— 所有 App 级设置共用同一个 `branchbase` prefs，前缀只会让键变长；
 * - 枚举落盘存 `name`（如 `"LOCAL_REPO"`），**不存 `ordinal`** —— 顺序一变就串档（§8.2）。
 *
 * > 例外：主题走 `ThemeMode.storageKey`（小写短名 `system` / `light` / `dark`）。
 * > 那是历史约定，**不要**为了「统一」而迁移它 —— 迁移会让老用户的主题设置读不回来。
 */
object SettingsKeys {

    /** App 级设置共用的 SharedPreferences 文件名。 */
    const val PREFS = "branchbase"

    /** 提交模式，值为 [com.branchbase.ui.profile.CommitMode] 的 `name`；缺失 = 未配置。 */
    const val COMMIT_MODE = "commit_mode"

    /** libgit2（本地仓库 clone / pull / push）的 HTTP 代理；空串 = 不使用代理。 */
    const val GIT_PROXY = "git_proxy"

    /** 通知列表显示模式，值为 [com.branchbase.ui.notification.NotifLayout] 的 `name`。 */
    const val NOTIF_LAYOUT = "notif_layout"

    /**
     * 统一的 SharedPreferences 句柄。
     *
     * 优先用 `applicationContext`：设置页可能在 Activity 重建的瞬间读写，
     * 持有 Activity Context 的引用会连带泄漏内存。
     */
    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** 读取已保存的 Git 代理（空串 = 不使用代理）。键与读取口都在这里，避免第二个真源。 */
fun gitProxy(context: Context): String =
    SettingsKeys.prefs(context).getString(SettingsKeys.GIT_PROXY, "") ?: ""
