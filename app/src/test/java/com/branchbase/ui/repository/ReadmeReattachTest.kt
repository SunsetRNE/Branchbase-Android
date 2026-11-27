package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「滑到页面底部、再往上滑，自述文件整块白屏」的**源码级钉子**（1.4.96，真机 2026-11-28）。
 *
 * ## 现场
 *
 * 仓库页滚到底（许可证 / 贡献者 / 语言）再往回滚，README 那片区域**持久白屏**：
 * 同一份文档、同一个 WebView、同一个高度都在（[ReadmeViewHolder] 的职责没丢），
 * 只是**画不出来**。返回上一页再进来反而正常 —— 新 WebView 会重新 `loadDataWithBaseURL`。
 *
 * ## 机制（Chromium HWUI 渲染路径）
 *
 * WebView detach（`LazyColumn` 回收这一项）时 `ReleaseHardware()` 把「已渲染过帧」置 false，
 * 并把一帧 `drawColor(WHITE)` 塞进 DisplayList；re-attach 后 compositor 即使交还了新帧，
 * 也**没有新的 `OnDrawHardware` 被触发** —— DisplayList 里那帧白色就一直重放。
 * WebView 内部的修法是在 `ReturnUsedResources` 里 PostInvalidate（openwebview bugfix 114），
 * 应用侧只能自己补：挂回窗口后 invalidate 一次，让 `OnDrawHardware` 重跑。
 *
 * ## 钉住什么
 *
 * 1. 必须监听 `OnAttachStateChangeListener`（不监听就没有「挂回」这个时机）；
 * 2. attach 回调里必须 `invalidate()`，且是 `post` 出去的（attach 当帧立刻重绘可能仍拿不到帧）；
 * 3. 只对**复用的** WebView 有意义，但新实例多一次 invalidate 无害 —— 不为此加分支。
 */
class ReadmeReattachTest {

    private fun source(): String =
        File("src/main/java/com/branchbase/ui/repository/ReadmeWebView.kt").readText()

    @Test
    fun `挂回窗口必须监听并主动重绘`() {
        val src = source()
        assertTrue(
            "WebView 被 LazyColumn 回收后重新挂回窗口时，必须监听 attach 事件才有时机补重绘",
            src.contains("addOnAttachStateChangeListener"),
        )
        assertTrue(
            "attach 回调必须触发 invalidate（这是让 OnDrawHardware 重跑的唯一应用侧手段）",
            src.contains("override fun onViewAttachedToWindow(v: View)") && src.contains("invalidate()"),
        )
    }

    @Test
    fun `重绘必须 post 到下一帧`() {
        val src = source()
        assertTrue(
            "attach 当帧立刻 invalidate 可能仍拿不到 compositor 的帧（白底会被再画一遍）",
            src.contains("v.post { v.invalidate() }"),
        )
    }
}
