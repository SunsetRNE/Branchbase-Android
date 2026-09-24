package com.branchbase.joblogs

/** 一段日志（按 `##[group]` 切分，标题通常等于步骤名）。 */
data class LogSegment(val title: String, val lines: List<String>) {
    companion object {
        /**
         * **没有发生分组**（整份日志一段）时的标题。
         *
         * ⚠️ 它既是展示文案，也是 [JobLogParser] 判断「有没有分组」的判据 ——
         * 所以只允许一处定义。两处各写一遍的话，改动一处就会让那条 `return` 提前或滞后，
         * 表现是日志分段静默错位（不报错、只是少了一段标题）。
         *
         * 展示层将来把这个标题换成资源时，判据要一并改成**结构化字段**
         * （例如 `title: String?`，null 表示未分组），而不是继续比对文案。
         */
        const val UNGROUPED_TITLE = "全部日志"
    }
}

/**
 * 一次取日志的成品：原文 + 已切好的分段。
 *
 * 两份数据都要留着，因为两个调用方的渲染需要不同：
 * Job 详情页要把整份日志原样直出；运行详情页只取「当前选中步骤」对应那一段。
 * 分段是唯一有 CPU 成本的部分，所以跟着原文一起缓存，避免每次重组重切。
 */
data class JobLog(
    val jobId: Long,
    val text: String,
    val segments: List<LogSegment>,
)
