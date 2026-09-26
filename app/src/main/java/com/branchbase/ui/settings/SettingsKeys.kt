package com.branchbase.ui.settings

import android.content.Context
import android.content.SharedPreferences
import com.branchbase.BuildConfig

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
     * 已丢弃的通知 thread id 集合（JSON 数组**保序**，见 `NotifDiscard`）。
     *
     * 「丢弃」只写本地：GitHub 没有「永久隐藏」接口，而用户要的是「别再让我看见它」。
     * 单独一份集合而不是直接删归档 —— 删了归档，远端仍会返回这条，它会在收件箱里**复活**。
     */
    const val NOTIF_DISCARDED = "notif_discarded"

    /**
     * 慢帧日志开关（`FrameWatch`）。
     *
     * **只有用户动过开关才有这个键** —— 缺失时用编译通道的默认值
     * （[BuildConfig.FRAME_WATCH_DEFAULT]：Beta 开、正式版关）。
     */
    const val FRAME_WATCH = "frame_watch"

    /**
     * 首页「常用仓库」的**置顶选择**（JSON 对象，见 `FrequentRepoStore`）：
     * `{"<login>": ["owner/repo", …]}`，数组**保序** —— 顺序就是首页显示顺序，
     * 后选的在后面（用户点选先后即排序，见 `FrequentRepoRules`）。
     *
     * 为什么按账号分桶：同一台设备可以登多个账号（见 `docs/specs/features-design.md` §1），
     * 而「常用」天然是每个账号各一份。为什么是**整体 JSON** 而不是 `键_账号`：
     * 账号一多键就散，且读一次就能拿到全部桶。
     *
     * **缺键 ≠ 空数组**：缺键 = 用户从未自定义过，首页回落到接口顺序取前 5；
     * 存在但该账号为空数组 = 用户明确清空了置顶，首页显示空态。两者不能混。
     */
    const val HOME_FREQUENT_REPOS = "home_frequent_repos"

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

/**
 * 慢帧日志是否开启。
 *
 * 默认值来自**编译通道**：Beta（debug / perfBeta）默认开、正式版默认关（见
 * `app/build.gradle.kts` 的 `FRAME_WATCH_DEFAULT`）。用户可以在
 * 「设置 → 关于与诊断 → 慢帧日志」里覆盖 —— 所以这里存的是**显式选择**，
 * 缺键时才回落到通道默认值。
 *
 * 读它的地方有两处：[com.branchbase.ui.log.FrameWatch.setEnabled] 的调用方（启动时）
 * 与设置页那一行开关。**不要在组合期直接读**（`SharedPreferences` 首次加载会读盘）。
 */
fun frameWatchEnabled(context: Context): Boolean =
    SettingsKeys.prefs(context).getBoolean(SettingsKeys.FRAME_WATCH, BuildConfig.FRAME_WATCH_DEFAULT)

/** 写入慢帧日志开关（调用方紧接着要同步 [com.branchbase.ui.log.FrameWatch.setEnabled]）。 */
fun setFrameWatchEnabled(context: Context, enabled: Boolean) {
    SettingsKeys.prefs(context).edit().putBoolean(SettingsKeys.FRAME_WATCH, enabled).apply()
}
