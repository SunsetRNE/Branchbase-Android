package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 草稿自动保存的**语义钉子**（源码级，套路同 `FileEditorWiringTest` / `RepoOverviewLoadTest`）。
 *
 * ## 用户问的是（2026-09-27）
 *
 * 「草稿莫名其妙的自动保存是什么情况？」—— 拆开是两个都不是 bug、但都不该照原样留着的行为：
 *
 * 1. `LaunchedEffect` **首次组合就会跑**：它是「键变了就重启」，不是「检测到变更」。
 *    所以进编辑页什么都不动，900ms 后也照样落盘一次并弹出「草稿已自动保存」；
 * 2. 提示赋值之后**再没人清**（`savedHint` 只在保存成功那一行被写），于是它会一直挂在
 *    「更新内容」上方，看起来更像自己冒出来的。
 *
 * ## 修完的行为（本测试守的就是它）
 *
 * - **照样静默保存**（「退出再回来不丢」是这个功能的全部意义，不能因为解释不清就删掉）；
 * - 只有「这次落盘的内容和上次真的不同」才提示 —— 与上一次比对的是内容指纹；
 * - 提示 2.5 秒后自己消失。
 */
class ReleaseDraftAutosaveTest {

    private val screenPath = "src/main/java/com/branchbase/ui/repository/ReleaseScreens.kt"

    private fun code(): String {
        val file = File(screenPath)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText().lines()
            .filterNot { it.trimStart().startsWith("import") }
            .joinToString("\n")
    }

    @Test
    fun `自动保存本身不许被删掉`() {
        val src = code()
        assertTrue(
            "「改任何字段 900ms 后落盘」是草稿功能的全部意义，只能改措辞不能删",
            src.contains("LaunchedEffect(tag, name, body, target, type, makeLatest, attachments)"),
        )
        assertTrue("落盘还是走 ReleaseDraftStore.save", src.contains("ReleaseDraftStore.save("))
        assertTrue("保存前要有 900ms 防抖（边打边存会把主线程和磁盘都拖住）", src.contains("delay(900)"))
    }

    @Test
    fun `进页面没动过就不该提示草稿已保存`() {
        val src = code()
        assertTrue("要有「上次落盘内容」的指纹", src.contains("var savedFingerprint by remember { mutableStateOf(draftFingerprint) }"))
        assertTrue("指纹要覆盖自动保存的全部键（tag/标题/正文/目标/类型/最新/附件）", src.contains("val draftFingerprint = listOf("))
        assertTrue("必须内容真的变了才提示", src.contains("val changed = draftFingerprint != savedFingerprint"))
        assertTrue(
            "提示要挂在 changed 上：首帧那次「什么都没改」的保存不该弹",
            src.contains("if (changed) savedHint = context.getString(R.string.toast_draft_autosaved)"),
        )
        assertFalse(
            "旧写法（无条件提示）会让「进页面什么都没动」也弹草稿已保存 —— 这就是「莫名其妙」的来源",
            src.contains("if (ok) savedHint = context.getString(R.string.toast_draft_autosaved)"),
        )
    }

    @Test
    fun `保存提示会自己消失`() {
        val src = code()
        assertTrue("提示要有一个跟着它自己的 effect", src.contains("LaunchedEffect(savedHint)"))
        assertTrue("提示要有存活时长", src.contains("delay(2500)"))
        assertTrue("到点要把它清掉（否则会一直挂在正文上方）", src.contains("savedHint = null"))
    }
}
