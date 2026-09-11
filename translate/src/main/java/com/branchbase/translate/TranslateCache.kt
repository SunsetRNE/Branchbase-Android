package com.branchbase.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 进程内 LRU（**纯 Kotlin 实现**，故意不用 `android.util.LruCache`）。
 *
 * 原因很实际：`android.util.LruCache` 在 JVM 单测里是空壳（方法直接抛 not mocked），
 * 缓存命中/淘汰这类「只有边界条件出错」的逻辑就完全测不到。这里自己写 30 行，
 * 换来的是能在 `./gradlew :translate:testDebugUnitTest` 里验证淘汰行为。
 *
 * 访问序 LRU：`LinkedHashMap(accessOrder = true)`，超出容量时淘汰最久未访问的一项。
 * 方法全部 `@Synchronized`：翻译在多协程里并发读写，而缓存本身足够小，锁粒度不是瓶颈。
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
    fun size(): Int = map.size

    @Synchronized
    fun clear() = map.clear()
}

/**
 * 译文磁盘缓存（跨进程复用）。
 *
 * ## 为什么进程内缓存不够
 *
 * 内存 LRU 只覆盖「本次进程活着的这段时间」。而这个 App 的使用方式恰恰相反：
 * 用户看一篇 README → 退出 → 过一会再打开同一篇 → 进程早就被系统回收，
 * 于是所有段落重新请求一遍，白烧匿名额度（MyMemory 约 5000 词/天）。
 * 网页版沉浸式翻译用 IndexedDB 做持久缓存，这里用等价的最小实现。
 *
 * ## 存储格式：**追加日志 + 定期压实**
 *
 * - 每行 `key\tvalue`（value 做转义），`put` 只追加一行 —— O(1) 写，不重写整个文件；
 * - 启动（首次访问）时全量读入内存，**后出现的行覆盖先出现的**（last-write-wins）；
 * - 条目数超过 [maxEntries] 时压实一次：按访问序重写文件，淘汰最久未用的条目。
 *
 * 文件损坏（半行、非法转义）时**跳过该行而不是抛异常**：缓存的正确失败方式是
 * 「当作没缓存」，绝不能因为缓存文件坏了让整个翻译功能不可用。
 *
 * @param file 缓存文件（:app 传 `filesDir/translate/cache.tsv`；单测传临时文件）
 * @param maxEntries 条目上限。一条译文平均 ~200 字节，2000 条约 400KB，可接受。
 */
class TranslateDiskCache(
    private val file: File,
    private val maxEntries: Int = 2000,
) {

    private val mutex = Mutex()
    private var loaded = false
    private val entries = LinkedHashMap<String, String>(64, 0.75f, true)

    /** 已追加但尚未压实的行数（达到 [maxEntries] 的一半就压实一次）。 */
    private var appended = 0

    suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            entries[key]
        }
    }

    suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            if (key.isBlank() || value.isBlank()) return@withLock
            entries[key] = value
            appended++
            runCatching { appendLine(key, value) }
            if (appended >= maxEntries / 2 || entries.size > maxEntries) compact()
        }
    }

    suspend fun size(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            entries.size
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            entries.clear()
            appended = 0
            loaded = true
            runCatching { file.delete() }
        }
    }

    // ── 内部实现 ──

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.isFile) return
            file.forEachLine { line ->
                val parsed = parseLine(line) ?: return@forEachLine
                // 后出现的覆盖先出现的：先删再插，保证 LRU 顺序里它是最新访问的
                entries.remove(parsed.first)
                entries[parsed.first] = parsed.second
            }
        }
        if (entries.size > maxEntries) compact()
    }

    private fun appendLine(key: String, value: String) {
        file.parentFile?.mkdirs()
        file.appendText(escape(key) + "\t" + escape(value) + "\n")
    }

    private fun compact() {
        // 先按最久未访问淘汰到容量以内（LinkedHashMap 的迭代序即访问序）
        while (entries.size > maxEntries) {
            val eldest = entries.entries.firstOrNull() ?: break
            entries.remove(eldest.key)
        }
        appended = 0
        runCatching {
            if (entries.isEmpty()) {
                file.delete()
                return
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                for ((k, v) in entries) {
                    w.write(escape(k))
                    w.write("\t")
                    w.write(escape(v))
                    w.newLine()
                }
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    private fun parseLine(line: String): Pair<String, String>? {
        val tab = line.indexOf('\t')
        if (tab <= 0) return null
        val key = unescape(line.substring(0, tab)) ?: return null
        val value = unescape(line.substring(tab + 1)) ?: return null
        if (key.isEmpty() || value.isEmpty()) return null
        return key to value
    }

    /** 转义：`\` → `\\`、换行 → `\n`、制表 → `\t`（译文里换行很常见，必须能落盘）。 */
    private fun escape(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    private fun unescape(s: String): String? = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') {
                append(c)
                i++
                continue
            }
            if (i + 1 >= s.length) return null
            when (s[i + 1]) {
                '\\' -> append('\\')
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                else -> return null
            }
            i += 2
        }
    }
}

/**
 * 译文缓存门面：内存 LRU（快）+ 磁盘（跨进程）。
 *
 * 读路径：内存 → 磁盘（命中后回填内存）；写路径：两边都写。
 * 磁盘是否启用由外部传入（设置页的「本地缓存」开关），关掉后 `disk()` 返回 null，
 * 已落盘的数据不动 —— 用户重新打开开关即恢复命中。
 */
class TranslateCache(
    memoryEntries: Int = 512,
    private val disk: () -> TranslateDiskCache? = { null },
) {

    private val memory = LruCache<String, String>(memoryEntries)

    suspend fun get(from: String, to: String, text: String): String? {
        val key = memoryKey(from, to, text)
        memory.get(key)?.let { return it }
        val hit = disk()?.get(diskKey(from, to, text)) ?: return null
        memory.put(key, hit)
        return hit
    }

    suspend fun put(from: String, to: String, text: String, translated: String) {
        if (translated.isBlank()) return
        memory.put(memoryKey(from, to, text), translated)
        disk()?.put(diskKey(from, to, text), translated)
    }

    fun memoryCount(): Int = memory.size()

    suspend fun diskCount(): Int = disk()?.size() ?: 0

    suspend fun clear() {
        memory.clear()
        disk()?.clear()
    }

    /** 内存键直接用原文（可读、便于调试；内存只放 512 条，容量可控）。 */
    private fun memoryKey(from: String, to: String, text: String) = "$from|$to|$text"

    /** 磁盘键用 SHA-1（定长、避免原文里的制表符/换行污染行格式）。 */
    private fun diskKey(from: String, to: String, text: String): String = sha1("$from|$to|$text")

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
