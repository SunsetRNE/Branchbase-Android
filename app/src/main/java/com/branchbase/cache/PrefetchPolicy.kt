package com.branchbase.cache

import android.content.Context
import android.net.ConnectivityManager

/**
 * 预加载触发场景。
 *
 * 区分场景的意义：不同场景的「收益确定性」不同，计费网络下要区别对待。
 */
enum class PrefetchReason {
    /** 用户点击仓库卡片（即将进入仓库页）—— 预取的数据**必然会被用到** */
    OpenRepo,

    /** 已进入仓库页（补取其他 tab 的第一页）—— 属于「可能用到」 */
    EnterRepo,

    /** 列表项可见（浏览期预热仓库信息）—— 属于「可能用到」 */
    ListVisible,

    /** App 启动 / 首页渲染 —— 属于「可能用到」 */
    AppStart,
}

/** 一次预加载要做的事。 */
data class PrefetchPlan(
    /** 仓库页四件套（仓库信息 / README / 语言 / 贡献者） */
    val overview: Boolean,
    /** 其他 tab 的第一页（代码树 / Issue / PR / 提交 / 发布） */
    val tabs: Boolean,
    /** 列表卡片的仓库信息预热 */
    val listWarm: Boolean,
    /**
     * 消息页首屏预取（`/notifications` 首屏 + 解析快照）。
     *
     * 存在的意义：消息页此前是「进入才发首屏请求」，用户点「消息」Tab 必然先看一屏骨架。
     * 首页本来就要请求一次通知来算未读数，把它做完整（列表 + 快照）并不增加总流量，
     * 只是把「进入页面后才发的请求」挪到「首页渲染阶段」。
     */
    val notifications: Boolean = false,
) {
    val isEmpty: Boolean get() = !overview && !tabs && !listWarm && !notifications

    companion object {
        val NONE = PrefetchPlan(overview = false, tabs = false, listWarm = false, notifications = false)
    }
}

/** 列表预热的最大条数（只预热首屏可能点到的前几个）。 */
const val PREFETCH_LIST_LIMIT = 5

/**
 * 预加载决策（纯函数，便于单测）。
 *
 * 规则：
 * - **OpenRepo / EnterRepo**：`overview` 只要还没新鲜就预取 —— 这不算额外流量，
 *   只是把「进入页面后才发的请求」提前，用户点进去时直接命中缓存；
 * - **tabs / listWarm / notifications** 是投机性流量：只在「用户开启预加载」且**当前网络不计费**时做；
 * - 已经新鲜（TTL 内）的资源不重复预取。
 */
fun planPrefetch(
    reason: PrefetchReason,
    metered: Boolean,
    userOptIn: Boolean,
    overviewFresh: Boolean,
): PrefetchPlan {
    val speculative = userOptIn && !metered
    return when (reason) {
        PrefetchReason.OpenRepo,
        PrefetchReason.EnterRepo,
        -> PrefetchPlan(overview = !overviewFresh, tabs = speculative, listWarm = false, notifications = false)

        // 首页是「消息页」最可能的入口：在这里就把消息首屏取回，进消息页直接直出
        PrefetchReason.AppStart -> PrefetchPlan(
            overview = false,
            tabs = false,
            listWarm = speculative,
            notifications = speculative,
        )

        PrefetchReason.ListVisible,
        -> PrefetchPlan(overview = false, tabs = false, listWarm = speculative, notifications = false)
    }
}

// ───────────────────────── 预加载环境（开关 / 计费网络） ─────────────────────────
// 消息页预加载与仓库预加载共用同一套判定；抽出来避免两处各写一份、判定口径漂移。

/** 预加载总开关（设置项，默认开）。 */
fun prefetchEnabled(context: Context): Boolean =
    context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
        .getBoolean("prefetch.enabled", true)

/** 当前网络是否计费（移动数据）。取不到状态时**按计费处理**（保守：不投机预取）。 */
fun networkMetered(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    cm.isActiveNetworkMetered
}.getOrDefault(true)
