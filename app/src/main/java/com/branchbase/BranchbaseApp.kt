package com.branchbase

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * 应用级 Coil 配置。
 *
 * Coil 默认配置对头像这类小图其实够用，这里显式化的目的是把几个关键项固定下来：
 * 1. **crossfade 淡入（180ms）** —— 配合 `Avatar` 的首字母占位，图片是淡入而不是突变
 * 2. **磁盘缓存 100MB / 独立目录 `cacheDir/image_cache`** —— 贡献者头像、README 内图片都走这里，
 *    二次访问零网络；独立目录也便于用户「清理缓存」时一并处理
 * 3. **内存缓存 25%**（Coil 默认值，写出来便于后续调优）
 *
 * 注意：**当前账号头像不走 Coil** —— 它由 [com.branchbase.core.AvatarCache] 落在
 * `filesDir/avatars/{login}.png`，登录时预热、按账号隔离，渲染时零网络、零闪烁。
 */
class BranchbaseApp : Application(), ImageLoaderFactory {

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
