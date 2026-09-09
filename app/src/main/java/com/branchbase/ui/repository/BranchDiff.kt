package com.branchbase.ui.repository

/** 统一 diff（unified diff）中的一行。 */
data class DiffLine(
    val kind: DiffLineKind,
    val oldLine: Int?,
    val newLine: Int?,
    val text: String,
)

enum class DiffLineKind { Hunk, Context, Add, Remove }

/**
 * 解析 GitHub compare API 的 files[].patch（unified diff 片段）为带行号的行序列。
 */
fun parseUnifiedDiff(patch: String?): List<DiffLine> {
    if (patch.isNullOrBlank()) return emptyList()
    return runCatching { parseUnifiedDiffLines(patch) }.getOrDefault(emptyList())
}

/** hunk 头：`@@ -12,5 +14,7 @@`，计数可省略（`@@ -12 +14 @@`）。 */
private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

private fun parseUnifiedDiffLines(patch: String): List<DiffLine> {
    val out = mutableListOf<DiffLine>()
    var oldNo = 1
    var newNo = 1
    var seenHunk = false

    // 末尾换行会产生一个「幻影空行」：整串以 \n 结尾时先去掉这一个字符
    for (raw in patch.removeSuffix("\n").split('\n')) {
        // CRLF 兼容：先剥掉行尾的 \r。
        val line = raw.removeSuffix("\r")

        if (isMetaLine(line, seenHunk)) continue

        val hunk = hunkStarts(line)
        if (hunk != null) {
            out += DiffLine(DiffLineKind.Hunk, null, null, line)
            oldNo = hunk.first
            newNo = hunk.second
            seenHunk = true
            continue
        }

        when {
            line.startsWith("+") -> {
                out += DiffLine(DiffLineKind.Add, null, newNo, line.substring(1))
                newNo++
            }

            line.startsWith("-") -> {
                out += DiffLine(DiffLineKind.Remove, oldNo, null, line.substring(1))
                oldNo++
            }

            line.startsWith(" ") -> {
                out += DiffLine(DiffLineKind.Context, oldNo, newNo, line.substring(1))
                oldNo++
                newNo++
            }

            line.isEmpty() -> {
                out += DiffLine(DiffLineKind.Context, oldNo, newNo, "")
                oldNo++
                newNo++
            }

            else -> {
                out += DiffLine(DiffLineKind.Context, oldNo, newNo, line)
                oldNo++
                newNo++
            }
        }
    }

    return out
}

/**
 * 文件头等元信息行，不参与行号推进。
 *
 * `--- ` / `+++ ` 只在**首个 hunk 头之前**算文件头：hunk 内部它们是内容行
 * （删除一行 `-- foo` 的 diff 文本就是 `--- foo`），无条件跳过会吞掉真实代码。
 */
private fun isMetaLine(line: String, seenHunk: Boolean): Boolean = line.startsWith("diff --git ") ||
    line.startsWith("index ") ||
    (!seenHunk && line.startsWith("--- ")) ||
    (!seenHunk && line.startsWith("+++ ")) ||
    line.startsWith("\\") ||
    line.startsWith("new file mode") ||
    line.startsWith("deleted file mode") ||
    line.startsWith("old mode") ||
    line.startsWith("new mode") ||
    line.startsWith("similarity index") ||
    line.startsWith("rename from") ||
    line.startsWith("rename to")

/** 解析 hunk 头中的旧/新起始行号；不是合法 hunk 头时返回 null。 */
private fun hunkStarts(line: String): Pair<Int, Int>? {
    val match = HUNK_HEADER.find(line) ?: return null
    val oldStart = match.groupValues[1].toIntOrNull() ?: return null
    val newStart = match.groupValues[2].toIntOrNull() ?: return null
    return oldStart to newStart
}
