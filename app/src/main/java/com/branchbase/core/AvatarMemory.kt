package com.branchbase.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 头像的**进程内解码缓存**：让「蓝底首字母 → 真图」那一下**只发生一次**。
 *
 * ## 为什么需要它（真机反馈：「头像会反复蓝底图标，然后才加载成真实图标」）
 *
 * `Avatar` 的占位层是「蓝底 + 首字母」，真图此前一律交给 Coil **异步**加载
 * （本地文件或网络 URL 都走它）。异步意味着**每一次重建都要重新经历一遍占位**：
 * 切 Tab、进出子页、打开 More 菜单……只要组合树重建，用户就会看到蓝底闪一下再变真图。
 * 头像在界面上出现得非常频繁（个人页、设置账户卡、More 菜单），这个闪就很显眼。
 *
 * 这里把**解码后的 Bitmap** 留在进程内：下一次组合在同一帧就能拿到它，
 * 不经过任何异步路径（Coil 的内存缓存虽然也快，但仍然要过一次调度与一帧）。
 *
 * ## 边界
 *
 * - **键含尺寸与 version**：同一账号在不同位置用不同尺寸渲染；`version` 是调用方的
 *   「强制重读」信号（长按头像刷新），进键后语义与之前一致；
 * - **容量 24 条**：一条 96×96 的 ARGB 图约 36KB，24 条不到 1MB；
 * - **拿不到就回落异步**：文件不存在或下载失败时返回 null，界面继续用蓝底占位，
 *   与之前的行为一致（不会因为缓存层出问题就不显示头像）。
 */
object AvatarMemory {

    private const val MAX_ENTRIES = 24

    private val cache = LruCache<String, Bitmap>(MAX_ENTRIES)

    /** 同步取（可能在组合期调用：命中就是同帧直出）。 */
    fun get(login: String, px: Int, version: Int): Bitmap? = cache.get(key(login, px, version))

    /**
     * 取或加载：先内存 → 本地文件 → （给了 URL 才）下载并落盘。
     *
     * 全程在 IO 线程；调用方拿到结果后改状态即可（`LaunchedEffect` 里用）。
     */
    suspend fun loadOrNull(
        context: Context,
        login: String,
        px: Int,
        version: Int,
        url: String?,
    ): Bitmap? {
        get(login, px, version)?.let { return it }
        if (login.isBlank() || px <= 0) return null

        val cached = AvatarCache.localFileOrNull(context, login)
        val file = cached ?: run {
            val remote = url?.takeIf { it.isNotBlank() } ?: return null
            // 落盘复用既有实现（原子写入 + 按 login 隔离）；失败就当作没有
            if (!AvatarCache.refresh(context, login, remote)) return null
            AvatarCache.localFileOrNull(context, login) ?: return null
        }

        val bitmap = decodeScaled(file, px) ?: return null
        cache.put(key(login, px, version), bitmap)
        return bitmap
    }

    /** 某账号换了头像（长按刷新）时清掉它的全部尺寸。 */
    fun invalidate(login: String) = cache.removeWhere { it.startsWith("$login|") }

    fun clear() = cache.clear()

    fun size(): Int = cache.size()

    /** 键：账号 + 目标像素 + 强制读版本。 */
    private fun key(login: String, px: Int, version: Int): String = "$login|$px|$version"

    /**
     * 按目标像素解码（`inSampleSize` 取 2 的幂）。
     *
     * 不做降采样的话，一张 460×460 的头像解码成 ARGB 要 800KB 以上，
     * 24 条就把内存吃掉了 —— 而它们画出来只有几十 dp。
     */
    private fun decodeScaled(file: File, px: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) {
            sample *= 2
        }
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
