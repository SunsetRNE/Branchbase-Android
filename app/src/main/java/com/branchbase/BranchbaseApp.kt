package com.branchbase

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.branchbase.core.RustTranslateEngine
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
