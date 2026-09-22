package com.branchbase.ui.log

import androidx.core.content.FileProvider

/**
 * 日志包的 FileProvider。
 *
 * ## 为什么要多这一层空子类
 *
 * `:downloader` 模块已经注册过一个 `androidx.core.content.FileProvider`
 * （authority `${applicationId}.downloader.files`，用来把下载好的 APK 交给系统安装器）。
 * Manifest 合并按**类名**判重：再注册同一个类、即使 authority 不同，也会因为
 * `android:authorities` / `FILE_PROVIDER_PATHS` 两项冲突而合并失败。
 *
 * 三个可选解法里这个最小也最不越界：
 * - 用 `tools:replace` 覆盖 → 会把 downloader 那份的 authority 与路径表一起改掉，**弄坏下载功能**；
 * - 复用 downloader 的 authority → 得去改另一个模块的资源，顺带把「日志目录」暴露进它的路径表，
 *   两个不相干的用途混在一起；
 * - **换一个类名**（本文件）→ 各自独立、互不影响，代价是 5 行空实现。
 *
 * 行为完全继承自 `FileProvider`：路径表仍读 `@xml/log_file_paths`（只暴露
 * `Download/Branchbase/`），`FileProvider.getUriForFile` 只认 authority、不关心具体类。
 */
class LogFileProvider : FileProvider()
