package com.branchbase.ui.repository

import android.util.LruCache
import com.branchbase.core.RustBridge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 沉浸式翻译的**翻译器**（原文 + 译文对照）。
 *
 * ## 职责划分
 *
 * - Rust（`core/src/translate.rs`）：一次请求翻一段，负责选服务与解析；
 * - **这里**：分片、缓存、串行化、失败降级；
 * - JS（`assets/immersive-translate.js`）：只在页面上找段落、插入译文、做开关。
 *
 * 这套分层不是洁癖：缓存必须跨页面复用（README → Issue 正文 → 发布说明常常有大段重复），
 * 串行必须全局唯一（服务端对并发不友好），这两件事只能放在过程内的单例里做。
 *
 * ## 三条硬约束
 *
 * 1. **单次长度上限**：MyMemory 匿名接口限 500 字符/次，这里按 [MAX_CHUNK] 切，
 *    并且**优先在句末切**——在词中间断开会让译文出现半句废话；
 * 2. **串行**：用 [gate] 互斥，一次只发一个请求。批量翻译时并发会立刻撞限流，
 *    而限流的表现是「整页都翻不出来」，比慢几秒糟糕得多；
 * 3. **失败只影响自己**：任一段失败返回 null，调用方插入空串跳过，不影响其余段落。
 */
object Translator {

    /** 单次请求的字符上限（服务端 500，留 50 余量给 URL 编码与语言标签）。 */
    private const val MAX_CHUNK = 450

    /** 译文字数上限：超长段落（贴日志）翻出来也没人看，截断避免白烧额度。 */
    private const val MAX_PARAGRAPH = 1800

    /** 缓存条数：一屏 README 大约 40~80 段，512 条足够覆盖几页内容。 */
    private const val CACHE_SIZE = 512

    // 键 = "from|to|原文"。LruCache 内部已同步，跨线程读写安全。
    private val cache = LruCache<String, String>(CACHE_SIZE)

    /** 全局串行闸门：服务端对并发请求不友好，并发会触发限流让整页翻译失败。 */
    private val gate = Mutex()

    /** 默认可直接使用的语言对（界面只需「中/英」两个方向）。 */
    const val EN = "en"
    const val ZH = "zh-CN"

    /**
     * 翻译一段文本（null = 本次翻译失败）。
     *
     * 超长段按 [MAX_CHUNK] 分片后**依次**翻译并拼接；任一分片失败则整段返回 null
     * （半截译文比不翻更容易误导）。
     */
    suspend fun translateParagraph(
        text: String,
        from: String = EN,
        to: String = ZH,
    ): String? {
        val src = text.trim()
        if (src.isEmpty()) return null
        val body = if (src.length > MAX_PARAGRAPH) src.take(MAX_PARAGRAPH) else src

        val key = "$from|$to|$body"
        cache.get(key)?.let { return it }

        val chunks = chunk(body, MAX_CHUNK)
        val out = StringBuilder()
        gate.withLock {
            for (c in chunks) {
                val piece = RustBridge.translate(c, from, to) ?: return null
                if (out.isNotEmpty()) out.append('\n')
                out.append(piece)
            }
        }
        val result = out.toString().trim()
        if (result.isEmpty()) return null
        cache.put(key, result)
        return result
    }

    /** 批量翻译（**串行**，顺序与入参一一对应；失败位为空串）。 */
    suspend fun translateAll(
        texts: List<String>,
        from: String = EN,
        to: String = ZH,
    ): List<String> = texts.map { t ->
        if (t.isBlank()) "" else translateParagraph(t, from, to).orEmpty()
    }

    /** 当前缓存了多少段译文（设置页展示用）。 */
    fun cachedCount(): Int = cache.size()

    fun clearCache() = cache.evictAll()
}

/**
 * 把长文本切成不超过 [limit] 字符的片段。
 *
 * 切分优先级：段落 → 句末（`.`/`!`/`?`/`。`/`！`/`？`）→ 空格 → 硬切。
 * 之所以不做「整段一次请求」：服务端硬上限 500 字符，超了会直接返回一条告警文本
 * （见 Rust 侧 `parse_response`），表现为「译文是一句英文错误」。
 */
internal fun chunk(text: String, limit: Int): List<String> {
    if (text.length <= limit) return listOf(text)
    val out = mutableListOf<String>()
    val sentences = text.split(Regex("(?<=[.!?。！？])\\s*"))
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) {
            out += current.toString().trim()
            current.clear()
        }
    }
    for (s in sentences) {
        if (s.isEmpty()) continue
        // 单句就超限：按空格退而求其次，再不行硬切
        if (s.length > limit) {
            flush()
            var rest = s
            while (rest.length > limit) {
                val cut = rest.lastIndexOf(' ', limit).takeIf { it > limit / 2 } ?: limit
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) current.append(rest)
            continue
        }
        if (current.length + s.length > limit) flush()
        current.append(s)
    }
    flush()
    return out.filter { it.isNotEmpty() }
}
