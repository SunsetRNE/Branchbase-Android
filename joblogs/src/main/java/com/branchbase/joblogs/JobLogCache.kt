package com.branchbase.joblogs

/**
 * 日志缓存的**窄接口**：模块内不引用 Room / PageCache / 任何 App 类型。
 *
 * `:app` 用 `PageCache` + `SearchCacheManager` 实现它（见 `JobLogWiring.kt`），
 * 单测可以直接塞内存桩，或干脆传 null（纯内存模式）。
 *
 * 语义与 PageCache 的「先直出 / 回源」两段式一一对应，这样接上模块后
 * 既有页面的 TTL、过期可直出、失败不覆盖旧值这些行为都原样保留。
 */
interface JobLogCache {

    /**
     * 先直出：命中「过期也能用」的缓存时返回日志原文，供页面立即渲染。
     * [force]（手动刷新）时必须返回 null，强制走回源。
     */
    suspend fun cached(key: String, force: Boolean): String?

    /**
     * 回源刷新：命中**未过期**缓存则直接返回（不重复联网）；否则调用 [fetch] 并写回。
     * [fetch] 返回 null 或抛错时返回 null，且**不写缓存**（旧数据仍可用）。
     */
    suspend fun refresh(key: String, force: Boolean, fetch: suspend () -> String?): String?
}
