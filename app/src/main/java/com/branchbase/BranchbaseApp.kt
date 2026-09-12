package com.branchbase

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.branchbase.core.AccountStore
import com.branchbase.core.RustTranslateEngine
import com.branchbase.downloader.AuthProvider
import com.branchbase.downloader.DownloaderConfig
import com.branchbase.downloader.DownloaderRuntime
import com.branchbase.downloader.VendorIslandExtensions
import com.branchbase.translate.TranslateRuntime
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
        // 沉浸式翻译：后端用 Rust（core/src/translate/：MyMemory 或用户自带的 DeepSeek Key），
        // 缓存落 filesDir。引擎拿到的是「取设置的函数」而不是设置快照 ——
        // 用户在设置页改 Key / 换后端之后，下一页就生效，不需要重启 App。
        TranslateRuntime.install(
            this,
            RustTranslateEngine { TranslateSettings.read(this) },
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
