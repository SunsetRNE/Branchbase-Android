package com.branchbase.ui.repository

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.branchbase.core.LruCache

/**
 * 仓库页的**首帧快照**：已经渲染过一遍的仓库留在进程内，再次进入时当帧就位。
 *
 * ## 现场（真机日志 2026-09-22，v1.0.67）
 *
 * 用户反馈「已经渲染过的，再次重复进入仓库的话，会有闪烁」。日志里同一份缓存明明全是命中的：
 * ```
 * 23:39:12.515 进入仓库详情页 SunsetRNE/Branchbase-Android
 * 23:39:12.516 L1 直出 …×8 / L1 命中 ×4        ← 数据一个字节都不用等
 * 23:39:12.540 WebView 首次创建 10ms（主线程）   ← 但整页是从零组合出来的
 * ```
 * 问题不在数据，在**首帧**：`readmeHtml` / `languages` / `contributors` / `repoInfo` 的初始值都是
 * null / 空列表，要等一次挂起的缓存读回来才填上。于是每一次重进都重放一遍
 * 「正在加载自述文件… → 内容」、骨架 → 内容、README 从 1dp 撑到几万 dp。
 * 数据是命中的，**观感却像重建**。
 *
 * ## 这里存什么
 *
 * 解析后的对象本身（不是 JSON）：再进来看的就是同一份内容，不需要重新解析，也不碰磁盘。
 * 键是 `owner/repo`；容量很小（[MAX]）—— 它服务的是「返回上一层、再点回来」这类来回操作，
 * 不是浏览器历史。
 *
 * ## 边界
 *
 * - **只在进程内**：重启 App 后仍然走缓存直出那条路（那已经是毫秒级）；
 * - README 正文是这里最大的一项（几十~几百 KB），所以容量按**条数**卡死在 [MAX]，
 *   不能按「用过的仓库都留着」来；
 * - 快照只是**首帧**用的，回源结果照样会覆盖它 —— 它不改变任何取数逻辑，
 *   命中与失效仍由 `SearchCacheManager` 的 TTL 说了算（见 [SearchCacheManager]）。
 */
internal class RepoOverviewMemory(private val maxEntries: Int = MAX) {

    companion object {
        /**
         * 留几个仓库。取 3 是「返回上一层 → 点回来」的典型来回深度（个人页 / 搜索 / 消息三条入口
         * 各一个）；再多就是拿堆换一个几乎用不到的命中率 —— 单条 README 正文可能有几百 KB。
         */
        const val MAX = 3
    }

    /** 一份已渲染过的页面快照。字段全部可空 / 可为空表 —— 「这一次没取到」不等于「没有」。 */
    internal data class Snapshot(
        val info: RepoInfo? = null,
        val readmeHtml: String? = null,
        /** README 上一次测到的高度：**首帧不跳**的关键（否则这一项从 1dp 撑起来）。 */
        val readmeHeight: Dp = 1.dp,
        val languages: List<LanguageStat> = emptyList(),
        val contributors: List<Contributor> = emptyList(),
    )

    private val cache = LruCache<String, Snapshot>(maxEntries)

    private fun key(owner: String, repo: String) = "$owner/$repo"

    /** 取快照（顺带把它挪到 LRU 队尾）。 */
    fun get(owner: String, repo: String): Snapshot? = cache.get(key(owner, repo))

    /** 有则改、无则建：分块落地，每块到达时单独调用。 */
    private fun update(owner: String, repo: String, block: (Snapshot) -> Snapshot) {
        val k = key(owner, repo)
        cache.put(k, block(cache.get(k) ?: Snapshot()))
    }

    fun putInfo(owner: String, repo: String, info: RepoInfo) = update(owner, repo) { it.copy(info = info) }

    fun putReadme(owner: String, repo: String, html: String) = update(owner, repo) { it.copy(readmeHtml = html) }

    fun putReadmeHeight(owner: String, repo: String, height: Dp) = update(owner, repo) { it.copy(readmeHeight = height) }

    fun putLanguages(owner: String, repo: String, languages: List<LanguageStat>) =
        update(owner, repo) { it.copy(languages = languages) }

    fun putContributors(owner: String, repo: String, contributors: List<Contributor>) =
        update(owner, repo) { it.copy(contributors = contributors) }

    /** 供测试用（也是「换账号」这类场景的复位口）。 */
    fun clear() = cache.clear()
}

/** 进程级实例：仓库页首帧快照。 */
internal val repoOverviewMemory = RepoOverviewMemory()
