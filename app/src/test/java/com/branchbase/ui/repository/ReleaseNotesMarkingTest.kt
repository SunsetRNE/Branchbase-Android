package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「更新内容」的两个纯函数：**行号槽的几何**与**生成说明的行标记**。
 *
 * 这两块是这一版最容易悄悄坏掉的地方（换行对齐、标记跟着编辑漂走），
 * 而它们只有真机上敲字才看得出来，所以把判定提出来钉住。
 */
class ReleaseNotesMarkingTest {

    // ── 行号槽：逻辑行 ↔ 偏移 ──

    @Test
    fun `行首偏移按换行切分_末行即使为空也算一行`() {
        assertEquals(listOf(0), lineStartOffsets(""))
        assertEquals(listOf(0), lineStartOffsets("abc"))
        assertEquals(listOf(0, 4), lineStartOffsets("abc\n"))
        assertEquals(listOf(0, 4, 5), lineStartOffsets("abc\n\nxyz"))
    }

    @Test
    fun `光标落在第几行`() {
        val text = "abc\ndef\n"
        assertEquals(1, lineOfOffset(text, 0))
        assertEquals(1, lineOfOffset(text, 3))   // 换行符本身还属于上一行
        assertEquals(2, lineOfOffset(text, 4))
        assertEquals(3, lineOfOffset(text, 8))
        // 越界要夹住，否则手一抖就抛异常
        assertEquals(3, lineOfOffset(text, 999))
        assertEquals(1, lineOfOffset(text, -5))
    }

    // ── 生成说明：插入 → 标记 → 丢弃 ──

    private val handWritten = "> 从 v1.0.38 升级可直接覆盖安装。\n\n### 校验\n- 见附件。\n"

    @Test
    fun `生成说明是追加_不再整段覆盖`() {
        val (merged, inserted) = insertGeneratedNotes(handWritten, "## 变更\n- 修了 A\n- 修了 B")
        assertTrue("手写内容必须原样保留", merged.contains("从 v1.0.38 升级可直接覆盖安装"))
        assertTrue(merged.contains("## 变更"))
        assertTrue("追加在最后", merged.indexOf("## 变更") > merged.indexOf("### 校验"))
        assertEquals(setOf("## 变更", "- 修了 A", "- 修了 B"), inserted)
        // 末尾补了换行，且中间只隔一个空行
        assertTrue(merged.endsWith("- 修了 B\n"))
        assertFalse(merged.contains("\n\n\n"))
    }

    @Test
    fun `正文为空时生成内容就是全部`() {
        val (merged, inserted) = insertGeneratedNotes("", "## 变更")
        assertEquals("## 变更\n", merged)
        assertEquals(setOf("## 变更"), inserted)
    }

    @Test
    fun `标记跟着内容走_行号漂移也不怕_改掉的行自动脱掉标记`() {
        val (merged, inserted) = insertGeneratedNotes(handWritten, "## 变更\n- 修了 A")
        // 在生成内容之前插三行：行号整体下移
        val shifted = "新的一行\n再一行\n又一行\n" + merged
        val lines = generatedLineIndices(shifted, inserted)
        val shiftedLines = shifted.split("\n")
        assertEquals(2, lines.size)
        lines.forEach { assertTrue("标记必须落在生成的那两行上", shiftedLines[it - 1] in inserted) }

        // 用户把生成的行改掉 → 标记消失
        val edited = shifted.replace("- 修了 A", "- 修了 A（我改的）")
        assertEquals(setOf("## 变更"), generatedLineIndices(edited, inserted).map { edited.split("\n")[it - 1] }.toSet())
    }

    @Test
    fun `空行不会被误标`() {
        assertTrue(generatedLineIndices("a\n\nb", setOf("")).isEmpty())
        assertTrue(generatedLineIndices("a\n\nb", emptySet()).isEmpty())
    }

    @Test
    fun `丢弃生成行只删它自己`() {
        val (merged, inserted) = insertGeneratedNotes(handWritten, "## 变更\n- 修了 A\n- 修了 B")
        val lines = generatedLineIndices(merged, inserted)
        val dropped = dropGeneratedNotes(merged, lines)
        assertFalse(dropped.contains("## 变更"))
        assertFalse(dropped.contains("- 修了 A"))
        assertTrue(dropped.contains("从 v1.0.38 升级可直接覆盖安装"))
        assertTrue(dropped.contains("### 校验"))
        assertFalse("连续空行要收敛", dropped.contains("\n\n\n"))
    }

    // ── 结构性钉子（源码级，与 FileEditorWiringTest 同一套路） ──

    @Test
    fun `编辑器必须按 TextLayoutResult 量行高_而不是数换行符`() {
        val source = File("src/main/java/com/branchbase/ui/repository/ReleaseNotesEditor.kt").readText()
        assertTrue("必须用 onTextLayout 拿布局结果", source.contains("onTextLayout"))
        assertTrue("折行对齐靠 getLineTop/getLineForOffset", source.contains("getLineTop") && source.contains("getLineForOffset"))
        assertTrue("标记列必须定宽（否则 + 出现时数字会左右跳）", source.contains("MARKER_W"))
        assertTrue("正文必须无框：不许出现 OutlinedTextField", !source.contains("OutlinedTextField"))
        assertTrue("不许再画分隔竖线（divider）", !source.contains("drawLine"))
    }

    @Test
    fun `编辑页必须把导入的文件落盘_而不是只留 Uri`() {
        val source = File("src/main/java/com/branchbase/ui/repository/ReleaseScreens.kt").readText()
        assertTrue("要用系统文件选择器", source.contains("rememberLauncherForActivityResult"))
        assertTrue("拿到 Uri 立刻复制进暂存区", source.contains("ReleaseAttachmentStore.stage"))
        assertTrue("表单 + 附件清单要能恢复", source.contains("ReleaseDraftStore"))
        assertTrue("附件必须先挂到 release 上再上传", source.contains("uploadReleaseAsset"))
        assertTrue("旧的固定 200dp 描边正文框必须已经拆掉", !source.contains("MarkdownBodyField"))
    }
}
