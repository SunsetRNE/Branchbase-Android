package com.branchbase.ui.notification

/**
 * 消息首屏**内存快照**（③ 预渲染的落点）。
 *
 * 需求链条：首页渲染阶段已经预取了 `/notifications` 首屏（见 [NotificationPrefetcher]），
 * 但「缓存里有 JSON」并不等于「消息页首帧就能出内容」——页面仍要等一次协程、一次解析，
 * 这一帧就是骨架屏。这里把**解析后的 `Notification` 列表**放在进程内存里，
 * 消息页首帧同步读取即可渲染，网络回源在后台进行。
 *
 * 三条约束：
 * 1. **只做加速，不做事实来源**：快照过期（[TTL_MS]）或为空时，页面照旧走原有加载路径；
 * 2. **原始时间戳**：快照存的是 `Notification.updatedAtMs`，相对时间在渲染期计算
 *    （见 [relativeTimeOf]）—— 否则「3 分钟前」会在快照里被冻结成常量；
 * 3. **线程安全**：预取在 `Dispatchers.Default` 写、页面在主线程读，字段用 `@Volatile`，
 *    列表整体替换而不是原地修改，读取方永远看到一份自洽的 List。
 */
object NotifSnapshot {

    /** 快照有效期：与 `PageCache` 的通知 TTL（2 分钟）一致，保持「同一份数据两种读法」不打架。 */
    private const val TTL_MS = 2 * 60 * 1000L

    @Volatile
    private var items: List<Notification> = emptyList()

    @Volatile
    private var atMs: Long = 0L

    /** threadId → 首屏内容预览（首页阶段一并预取，见 [NotificationPrefetcher]）。 */
    @Volatile
    private var previews: Map<String, NotificationPreview> = emptyMap()

    /** 当前快照（可能为空列表；调用方可用 [isFresh] 判断能不能直接直出）。 */
    val value: List<Notification> get() = items

    /** 当前预览表（只增不减，避免「取回来的预览又消失」）。 */
    val previewMap: Map<String, NotificationPreview> get() = previews

    /** 快照能否直接用于首帧渲染。 */
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        items.isNotEmpty() && nowMs - atMs < TTL_MS

    /** 快照里的未读数：底部导航徽标与首页「待处理」卡片共用同一口径。 */
    fun unreadCount(): Int = items.count { it.unread }

    /** 整表替换（预取成功后调用）。 */
    fun update(list: List<Notification>, nowMs: Long = System.currentTimeMillis()) {
        items = list
        atMs = nowMs
    }

    /**
     * 页面本地改动（标记已读 / 完成 / 恢复未读）后同步快照。
     * 同步的意义：首页徽标与消息页列表读的是同一份数据，不同步就会出现「回首页还是旧数字」。
     */
    fun mutate(transform: (List<Notification>) -> List<Notification>) {
        items = transform(items)
        atMs = System.currentTimeMillis()
    }

    fun putPreview(preview: NotificationPreview) {
        previews = previews + (preview.threadId to preview)
    }

    /** 使快照失效（下拉强制刷新、全部已读后需要重新回源时调用）。 */
    fun invalidate() {
        items = emptyList()
        atMs = 0L
    }

    /** 退出登录 / 切换账号时清空，避免串号。 */
    fun clear() {
        invalidate()
        previews = emptyMap()
    }
}
