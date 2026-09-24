package com.branchbase.joblogs

/** GitHub 日志行首的 ISO 时间戳（`2026-09-07T13:42:35.1234567Z `）。 */
private val LOG_TIMESTAMP = Regex("^\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z ?")

/** 去掉 GitHub 日志行首的 ISO 时间戳（保留正文，便于阅读）。 */
fun stripLogTimestamp(line: String): String = LOG_TIMESTAMP.replaceFirst(line, "")

/**
 * 把 job 日志按 `##[group]…##[endgroup]` 切成段。
 *
 * GitHub 的日志里每个 step 通常对应一个 group（`run:` 步骤的 group 名是步骤名或命令行，
 * `uses:` 步骤是 `Run owner/repo@ref`），因此可以据此把日志归到具体步骤下。
 * 没有 group 标记时返回单段「全部日志」，绝不丢内容。
 *
 * 只收日志原文：**不**接收步骤列表。以前那个 `steps` 形参在函数体里从头到尾没被读过
 * （分组纯粹由 `##[group]` 标记决定），而「段落标题 ↔ 步骤名」的匹配需要步骤模型，
 * 那属于 :app 的渲染关切，留在 :app 侧做（见 `WorkflowModels.logSegmentForStep`）。
 */
fun splitJobLogBySteps(log: String): List<LogSegment> {
    if (log.isBlank()) return emptyList()
    val segments = mutableListOf<LogSegment>()
    var currentTitle: String? = null
    var current = mutableListOf<String>()

    fun flush() {
        // 只有空行的段落没有信息量（`##[endgroup]` 之后常跟一个空行，
        // 以前会多出一段「全部日志」+ 一行空串），丢掉；
        // 「绝不丢内容」约束的是正文，不是填充空行。
        if (current.any { it.isNotBlank() }) {
            segments += LogSegment(currentTitle ?: LogSegment.UNGROUPED_TITLE, current.toList())
        }
        current = mutableListOf()
    }

    log.split('\n').forEach { raw ->
        val line = stripLogTimestamp(raw.trimEnd('\r'))
        val groupAt = line.indexOf("##[group]")
        when {
            groupAt >= 0 -> {
                flush()
                currentTitle = line.substring(groupAt + "##[group]".length).trim()
            }

            line.contains("##[endgroup]") -> {
                flush()
                currentTitle = null
            }

            else -> current += line
        }
    }
    flush()

    // 完全没有 group：内容整体落在「全部日志」一段里，直接返回
    if (segments.size == 1 && segments[0].title == LogSegment.UNGROUPED_TITLE) return segments
    return segments
}
