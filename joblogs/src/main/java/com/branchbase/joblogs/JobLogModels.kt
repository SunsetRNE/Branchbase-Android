package com.branchbase.joblogs

/** 一段日志（按 `##[group]` 切分，标题通常等于步骤名）。 */
data class LogSegment(val title: String, val lines: List<String>)

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
