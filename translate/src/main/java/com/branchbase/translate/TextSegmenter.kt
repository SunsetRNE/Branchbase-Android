package com.branchbase.translate

/**
 * 长文本分片（把一段拆成若干不超过 `limit` 字符的片段）。
 *
 * ## 为什么必须存在
 *
 * MyMemory 单次 `q` 上限 500 字符，**超了不报错**：它会把
 * `QUERY LENGTH LIMIT DONE. MAX ALLOWED QUERY : 500 CHARS` 当成「译文」返回
 * （见 `core/src/translate.rs::parse_response`）。不切片的直接后果是页面上
 * 「译文是一句英文错误提示」——这是本项目最早踩到、也最容易被忽略的坑。
 *
 * ## 切分优先级
 *
 * 段落 → 句末（`.` `!` `?` `。` `！` `？`）→ 空格 → 硬切。
 *
 * 在词中间断开会让译文出现半句废话，所以先按句子攒；单句就超限时才退到空格；
 * 连空格都没有（贴了一串哈希/URL）才硬切。
 *
 * 拼接约定：调用方用 `\n` 连接各片译文（见 [Translator]），因此这里只保证
 * **不丢字符**（空白会被归一化，见 `切出来的片段拼回去内容不丢` 单测）。
 */
internal fun chunk(text: String, limit: Int): List<String> {
    if (limit <= 0) return listOf(text)
    if (text.length <= limit) return listOf(text)

    val out = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
        if (current.isNotEmpty()) {
            out += current.toString().trim()
            current.clear()
        }
    }

    for (sentence in text.split(SENTENCE_END)) {
        if (sentence.isEmpty()) continue

        // 单句就超限：按空格退而求其次，再不行硬切
        if (sentence.length > limit) {
            flush()
            var rest = sentence
            while (rest.length > limit) {
                // 断点必须落在片段后半段，否则会出现「一个词切一片」的碎片
                val cut = rest.lastIndexOf(' ', limit).takeIf { it > limit / 2 } ?: limit
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) current.append(rest)
            continue
        }

        if (current.length + sentence.length > limit) flush()
        current.append(sentence)
    }
    flush()
    return out.filter { it.isNotEmpty() }
}

/** 在句末标点之后切（保留标点在前一片里，`(?<=…)` 是零宽断言）。 */
private val SENTENCE_END = Regex("(?<=[.!?。！？])\\s*")
