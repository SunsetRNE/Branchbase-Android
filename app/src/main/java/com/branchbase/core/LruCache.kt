package com.branchbase.core

/**
 * 极小的访问序 LRU（纯 Kotlin，不碰 Android）。
 *
 * 为什么自己写而不是用 `android.util.LruCache`：那个类在 JVM 单测里是空壳
 * （方法直接抛 not mocked），而「淘汰顺序」这种只有边界条件才出错的逻辑恰恰最该被测到。
 * 与 `:translate` 模块里那份同源（模块之间不互相依赖，各留一份 30 行的实现更省事）。
 */
internal class LruCache<K : Any, V : Any>(private val maxEntries: Int) {

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun remove(key: K) {
        map.remove(key)
    }

    /** 删掉所有满足条件的项（例如「某账号换了头像」时按前缀清）。 */
    @Synchronized
    fun removeWhere(predicate: (K) -> Boolean) {
        val it = map.keys.iterator()
        while (it.hasNext()) if (predicate(it.next())) it.remove()
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun clear() = map.clear()
}
