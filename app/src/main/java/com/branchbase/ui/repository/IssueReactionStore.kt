package com.branchbase.ui.repository

import android.content.Context

/**
 * 「我的反应」本地登记表。
 *
 * 为什么必须本地记：GitHub 的 reactions **计数对象**（`reactions: {"+1": 2, ...}`）
 * 只给总数，不告诉你「其中哪一个是我」；而撤销反应又必须用 **reaction id** 走 DELETE。
 * 想拿 id 只有两条路：
 * 1. 再拉一次 `/reactions` 列表（每条评论一次请求）—— 时间线里十几条评论就是十几次请求，
 *    完全不可接受；
 * 2. 添加反应时把响应里的 id 记下来 —— 一次写入，后续撤销零成本。
 *
 * 这里选 2：键是 `owner/repo#issue:scope:content`，值是 reaction id。
 * 表随账号无关（reaction id 全局唯一），因此不按 login 隔离。
 */
object IssueReactionStore {

    private const val KEY = "issue_my_reactions"

    /** 上限：反应本身是低频操作，超过后按写入顺序丢弃最早的一批。 */
    private const val MAX = 200

    private fun prefs(context: Context) =
        context.getSharedPreferences("branchbase", Context.MODE_PRIVATE)

    /** 读取全部登记（key → reaction id）。 */
    private fun all(context: Context): MutableMap<String, Long> {
        val raw = prefs(context).getString(KEY, null) ?: return LinkedHashMap()
        val map = LinkedHashMap<String, Long>()
        raw.split('\n').forEach { line ->
            val idx = line.lastIndexOf('=')
            if (idx <= 0) return@forEach
            line.substring(idx + 1).toLongOrNull()?.let { map[line.substring(0, idx)] = it }
        }
        return map
    }

    private fun write(context: Context, map: Map<String, Long>) {
        val lines = map.entries.toList().takeLast(MAX).joinToString("\n") { "${it.key}=${it.value}" }
        prefs(context).edit().putString(KEY, lines).apply()
    }

    fun idOf(context: Context, key: String): Long? = all(context)[key]

    fun isMine(context: Context, key: String): Boolean = all(context).containsKey(key)

    fun put(context: Context, key: String, id: Long) {
        val map = all(context)
        map[key] = id
        write(context, map)
    }

    fun remove(context: Context, key: String) {
        val map = all(context)
        map.remove(key)
        write(context, map)
    }
}
