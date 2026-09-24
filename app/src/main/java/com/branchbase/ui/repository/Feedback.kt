package com.branchbase.ui.repository

/**
 * 一次操作的反馈：文案 + 结果语气。
 *
 * **语气由产生方给出**（它手上就有 `err == null` / `ok` 这个事实），渲染层不猜。
 * 猜的写法是 `msg.contains("失败")` 或 `msg == "已提交"` ——
 *
 * - 前者在文案抽成资源、界面切成英文后 `contains("失败")` 永不成立，失败被渲染成绿色；
 * - 后者更直接：`"已提交 ${'$'}{n} 个文件"` 根本不等于 `"已提交"`，
 *   **今天**就已经把成功消息渲染成红色（`RepositoryFileViewer` 里真实存在的一类）。
 *
 * 两者都不崩溃、不报错，只是静默错色 —— 所以判据必须来自产生方，而不是文案本身。
 *
 * `ok = true` 覆盖「成功」与「无需操作」两种情况（现状两者同为绿色；
 * 「没有可提交的内容」这类中性结果按 warning 处理，走 `ok = false`）。
 */
internal data class Feedback(val text: String, val ok: Boolean)
