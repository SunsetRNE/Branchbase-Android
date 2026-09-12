package com.branchbase.ui.notification

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 消息页的**本地状态**（两份，都落在 `branchbase` SharedPreferences）。
 *
 * 为什么必须有本地状态：
 * - GitHub 的 REST API 只有「标记已读 / 标记 done」两个写操作，**没有「标记未读」**，
 *   而「撤销」「恢复未读」是本地体验的一等公民；远端不可逆的操作必须由本地覆盖；
 * - 「已完成 / 过往 Issue」在 GitHub 侧没有对应列表（官方 App 也是本地维护 done 状态），
 *   网络返回的 `/notifications?all=true` 只区分 read/unread，不区分 done。
 *
 * 两者都是「覆盖层」：远端返回什么照旧解析，本地集合只做**额外的状态覆盖与历史留存**，
 * 因此远端语义变化（限流、失败、字段新增）不会让页面读不出东西。
 */

/** 本地「已读」thread 集合（键：`notif_read_order`，JSON 数组保序；旧键 `notif_read_ids` 仍可读）。 */
object NotifReadStore {

    private const val KEY_LEGACY = "notif_read_ids"

    /**
     * 保序键。
     *
     * 为什么不能继续用 `StringSet`：`getStringSet` 返回的是**无序**集合，
     * 「超过 500 条淘汰最早写入的」在无序集合上退化成「淘汰哈希序靠后的一批」——
     * 刚点开的那条可能当场被丢掉，下次回源又变回未读。
     */
    private const val KEY = "notif_read_order"

    /** 条数上限：读集合会随使用无限增长，超过后丢弃最早写入的一批（保留最近 500 个 thread）。 */
    private const val MAX = 500

    fun ids(context: Context): Set<String> = ordered(context).toSet()

    /** 按写入顺序读出（旧数据没有顺序信息，只能按集合原样读出）。 */
    private fun ordered(context: Context): List<String> {
        val prefs = context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { raw ->
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }.getOrDefault(emptyList())
        }
        return (prefs.getStringSet(KEY_LEGACY, emptySet()) ?: emptySet()).toList()
    }

    fun add(context: Context, ids: Collection<String>): Set<String> {
        // LinkedHashSet：已存在的不改变位置，新加的排在最后 → takeLast(MAX) 才是「淘汰最旧的」
        val next = LinkedHashSet(ordered(context))
        next.addAll(ids)
        val kept = if (next.size > MAX) next.toList().takeLast(MAX) else next.toList()
        write(context, kept)
        return kept.toSet()
    }

    fun remove(context: Context, ids: Collection<String>): Set<String> {
        val next = ordered(context).filterNot { it in ids.toSet() }
        write(context, next)
        return next.toSet()
    }

    fun replace(context: Context, ids: Set<String>) {
        // 撤销路径给的是集合（顺序已丢失），只能原样写回
        write(context, ids.toList())
    }

    /** 把远端结果按本地已读集合覆盖一遍（页面与预取器共用同一口径）。 */
    fun apply(context: Context, list: List<Notification>): List<Notification> {
        val read = ids(context)
        if (read.isEmpty()) return list
        return list.map { if (it.unread && it.id in read) it.copy(unread = false) else it }
    }

    private fun write(context: Context, ids: List<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}

/**
 * 「过往 Issue / 已完成」本地归档条目。
 *
 * 只存渲染一行所需的字段（标题、仓库、编号、类型、动因、时间），
 * 回读时用 [toNotification] 还原成与网络同构的 [Notification]，
 * 这样「已完成」分类可以直接复用同一套行渲染，不需要第二套 UI。
 */
data class ArchivedThread(
    val id: String,
    val title: String,
    val repoFullName: String,
    val number: Long?,
    val subjectType: String,
    val reason: String,
    val updatedAtMs: Long,
    /** `done` = 用户显式「完成」；`read` = 读过后自动留存（用于「过往 Issue」） */
    val state: String,
    val archivedAtMs: Long,
) {
    val owner: String get() = repoFullName.substringBefore('/')
    val repo: String get() = repoFullName.substringAfter('/', "")

    val isDone: Boolean get() = state == STATE_DONE

    fun toNotification(): Notification = notificationOf(
        id = id,
        unread = false,
        reason = reason,
        subjectType = subjectType,
        title = title,
        // ⚠️ 必须补一个可解析的 URL：编号（targetNumber）是从 url 里抽出来的，
        // 归档条目只存了 number，不给 url 的话「已完成 / 过往 Issue」列表会丢掉 `#编号`，
        // 深链接也会退化成 issue #0。这里按 API 形态拼回去，让派生规则与网络解析完全一致。
        url = number?.let {
            val kind = if (subjectType == "PullRequest") "pulls" else "issues"
            "https://api.github.com/repos/$repoFullName/$kind/$it"
        }.orEmpty(),
        latestCommentUrl = null,
        repoFullName = repoFullName,
        updatedAtMs = updatedAtMs,
    )

    companion object {
        const val STATE_DONE = "done"
        const val STATE_READ = "read"
    }
}

/**
 * 归档存储（键：`notif_archive`，JSON 数组，LRU 上限 [MAX]）。
 *
 * 写入时机：
 * - 点开一条通知（已读）→ `read`，让「过往 Issue」能回看处理过的会话；
 * - 批量 / 单条「完成」→ `done`，从收件箱移出并进「已完成」；
 * - 「恢复未读」→ 从归档删除，并把该条重新插回列表顶部。
 */
object NotifArchive {

    private const val KEY = "notif_archive"
    private const val MAX = 300

    fun entries(context: Context): List<ArchivedThread> = runCatching {
        val raw = context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i -> parse(arr.optJSONObject(i)) }
    }.getOrDefault(emptyList())

    /** 批量写入（同 id 覆盖：done 优先于 read）。 */
    fun put(context: Context, list: List<ArchivedThread>) {
        if (list.isEmpty()) return
        val byId = LinkedHashMap<String, ArchivedThread>()
        // 先放旧的（保持原顺序），再用新的覆盖
        entries(context).forEach { byId[it.id] = it }
        list.forEach { new ->
            val old = byId[new.id]
            // done 是不可降级的终态：已有 done 就不被新的 read 覆盖
            if (old != null && old.isDone && !new.isDone) return@forEach
            byId[new.id] = new
        }
        val all = byId.values.toList()
        if (all.size <= MAX) {
            write(context, all)
            return
        }
        // 容量淘汰**优先丢自动留存的 read**：done 是用户显式操作的终态，
        // 被「过往 Issue」挤掉就再也找不回来了（注释里写了 done 不可降级，淘汰路径同样要守）。
        val overflow = all.size - MAX
        val evicted = all.filterNot { it.isDone }.take(overflow).map { it.id }.toSet()
        val kept = all.filterNot { it.id in evicted }
        write(context, if (kept.size > MAX) kept.takeLast(MAX) else kept)
    }

    /** 由通知列表生成归档条目。 */
    fun of(list: List<Notification>, state: String, nowMs: Long = System.currentTimeMillis()): List<ArchivedThread> =
        list.map { n ->
            ArchivedThread(
                id = n.id,
                title = n.title,
                repoFullName = n.repoFullName,
                number = n.targetNumber,
                subjectType = n.subjectType,
                reason = n.reason,
                updatedAtMs = n.updatedAtMs,
                state = state,
                archivedAtMs = nowMs,
            )
        }

    /** 移除并返回（恢复未读）。 */
    fun take(context: Context, id: String): ArchivedThread? {
        val all = entries(context)
        val hit = all.firstOrNull { it.id == id } ?: return null
        write(context, all.filterNot { it.id == id })
        return hit
    }

    fun remove(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        write(context, entries(context).filterNot { it.id in set })
    }

    /** 替换整表（撤销用：回到操作前的归档状态）。 */
    fun replace(context: Context, list: List<ArchivedThread>) = write(context, list)

    private fun write(context: Context, list: List<ArchivedThread>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(t: ArchivedThread): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("title", t.title)
        put("repo", t.repoFullName)
        put("number", t.number ?: 0L)
        put("type", t.subjectType)
        put("reason", t.reason)
        put("updatedAt", t.updatedAtMs)
        put("state", t.state)
        put("archivedAt", t.archivedAtMs)
    }

    private fun parse(o: JSONObject?): ArchivedThread? {
        if (o == null) return null
        val id = o.optString("id")
        if (id.isBlank()) return null
        return ArchivedThread(
            id = id,
            title = o.optString("title"),
            repoFullName = o.optString("repo"),
            number = o.optLong("number").takeIf { it > 0 },
            subjectType = o.optString("type", "Issue"),
            reason = o.optString("reason", "subscribed"),
            updatedAtMs = o.optLong("updatedAt"),
            state = o.optString("state", ArchivedThread.STATE_READ),
            archivedAtMs = o.optLong("archivedAt"),
        )
    }
}
