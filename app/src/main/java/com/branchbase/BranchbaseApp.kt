package com.branchbase

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.branchbase.core.AccountStore
import com.branchbase.core.NetworkWatch
import com.branchbase.core.RustTranslateEngine
import com.branchbase.downloader.AuthProvider
import com.branchbase.downloader.DownloaderConfig
import com.branchbase.downloader.DownloaderRuntime
import com.branchbase.downloader.VendorIslandExtensions
import com.branchbase.translate.TranslateRuntime
import com.branchbase.ui.log.LogManager
import com.branchbase.ui.log.Logger
import com.branchbase.translate.TranslateSettings

/**
 * 应用级配置与应用级组件的装配点。
 *
 * ## Coil 配置
 *
 * Coil 默认配置对头像这类小图其实够用，这里显式化的目的是把几个关键项固定下来：
 * 1. **crossfade 淡入（180ms）** —— 配合 `Avatar` 的首字母占位，图片是淡入而不是突变
 * 2. **磁盘缓存 100MB / 独立目录 `cacheDir/image_cache`** —— 贡献者头像、README 内图片都走这里，
 *    二次访问零网络；独立目录也便于用户「清理缓存」时一并处理
 * 3. **内存缓存 25%**（Coil 默认值，写出来便于后续调优）
 *
 * 注意：**当前账号头像不走 Coil** —— 它由 [com.branchbase.core.AvatarCache] 落在
 * `filesDir/avatars/{login}.png`，登录时预热、按账号隔离，渲染时零网络、零闪烁。
 *
 * ## 沉浸式翻译的装配
 *
 * [TranslateRuntime] 需要「后端引擎 + 缓存目录 + 设置」三样东西，其中只有引擎来自 App
 * （Rust 侧实现）。装配收在这里一行，正文页（`ReadmeWebView`）就只负责用，
 * 不需要知道缓存放在哪、用哪家翻译服务。
 */
class BranchbaseApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // 日志管理器**最先**起：`LogManager.init` 里的建目录 / 清历史是启动路径上第一笔磁盘 IO，
        // 而它此前在 `MainActivity.onCreate` 里 —— Application 阶段的日志只进内存环形缓冲、
        // 永远进不了文件（导出读的是文件）。真机证据：`NetworkWatch.install` 每次启动都会打一行
        // 基线，而整份 907 行日志里 `[Reach]` 只出现过 1 次。init 幂等，`MainActivity` 那次是兜底。
        Logger.ui("启动 ▸ 日志初始化（建目录 / 清历史）", "启动")
        LogManager.init(this)

        // 远端可达性守望：盯默认网络（含 VPN 接入 / 断开），跃迁时丢连接池并重探远端。
        // 必须**最先**装：晚于任何一次网络请求的话，基线就落在旧网络上，
        // 「先没开 VPN 打开 App、之后才接入 VPN」这一档会被漏掉（见 NetworkWatch 类注释）。
        // 这行「启动 ▸」是给慢帧注脚用的阶段标记：启动段本来就是「主线程被谁占住」的黑盒，
        // 唯一的归因通道是「最近一条 UI 类日志」，见 frame-perf-design.md §8/§9。
        Logger.ui("启动 ▸ 应用装配（Application.onCreate）", "启动")
        NetworkWatch.install(this)
        // 沉浸式翻译：后端用 Rust（core/src/translate/：MyMemory 或用户自带的 DeepSeek Key），
        // 缓存落 filesDir。引擎拿到的是「取设置的函数」而不是设置快照 ——
        // 用户在设置页改 Key / 换后端之后，下一页就生效，不需要重启 App。
        TranslateRuntime.install(
            this,
            RustTranslateEngine { TranslateSettings.read(this) },
            // 每批一行汇总（命中 / 未命中 / 变体）：翻译缓存「有没有在干活」此前完全不可见 ——
            // 命中率低说明键对不上（后端 / 模型 / 保护开关变了，或原文归一化不一致），
            // 而不是「缓存没生效」。按批记一行，不按段落刷屏。
            log = { Logger.net(it, "翻译") },
        )
        // 内建下载：注入「凭据」与「通知小图标」两样 App 侧才知道的东西。
        DownloaderRuntime.install(
            this,
            DownloaderConfig(
                smallIconRes = R.drawable.ic_stat_download,
                auth = AuthProvider { url -> authorizationForHost(url) },
                // 下载进度除了通知栏，再尝试投到厂商的「灵动岛 / 实时活动」：
                // 谷歌实时更新（Android 16）/ 小米超级岛 / OPPO 实况通知。
                // 三家都要白名单或额外依赖，扩展内部各自判断可用性；不可用时保持普通通知。
                islandExtensions = VendorIslandExtensions.defaults(),
            ),
        )
    }

    /**
     * 只给 GitHub 自有域名附带 token。
     *
     * 引擎在**每一跳重定向**都会回调这里，所以「GitHub 附件 302 到对象存储」时
     * token 不会跟着过去 —— 这正是把策略放在 provider 而不是引擎里的原因。
     */
    private fun authorizationForHost(url: String): String? {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return null
        val account = AccountStore.current(this) ?: return null
        val clientHost = account.host.lowercase()
        val allowed = host == clientHost ||
            host == "github.com" ||
            host.endsWith(".github.com") ||
            host == "api.github.com" ||
            host == "raw.githubusercontent.com"
        return if (allowed && account.token.isNotBlank()) "token ${account.token}" else null
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(100L * 1024 * 1024)
                .build()
        }
        .crossfade(180)
        .build()
}
