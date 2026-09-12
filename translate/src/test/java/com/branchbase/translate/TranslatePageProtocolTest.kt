package com.branchbase.translate

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面 ↔ 原生协议单测（状态快照 + 命令）。
 *
 * 这条通道两侧是两种语言，改一边忘一边**不会报错**，只会「面板上的数字不动」
 * 或「点了开关没反应」——最难查的那类问题。这里把三条契约钉死：
 *
 * 1. 快照解析按**不可信输入**处理（页面是 GitHub 的 HTML）：字段缺失/类型不对/JSON 坏
 *    都不能把原生的悬浮控件带崩；
 * 2. 命令名与页面脚本 `command()` 的 switch 分支一一对应（直接读 `03-boot.js` 核对）；
 * 3. 下发的命令是一行**转义过的** JS，参数里有引号也逃不出字符串字面量。
 */
class TranslatePageProtocolTest {

    private fun script(name: String): String {
        val file = File("src/main/assets/translate/$name")
        assertTrue("找不到页面脚本：${file.absolutePath}（单测工作目录应为 translate 模块根）", file.exists())
        return file.readText()
    }

    // ── 快照解析 ──

    @Test
    fun `快照按字段解析`() {
        val snap = TranslatePageSnapshot.parse(
            """{"on":true,"status":"translating","translated":12,"candidates":48,"chars":3240}""",
        )!!
        assertTrue(snap.on)
        assertEquals(TranslatePageSnapshot.STATUS_TRANSLATING, snap.status)
        assertEquals(12, snap.translated)
        assertEquals(48, snap.candidates)
        assertEquals(3240, snap.chars)
        assertTrue(snap.busy)
        assertFalse(snap.blocked)
    }

    @Test
    fun `缺字段走默认值`() {
        val snap = TranslatePageSnapshot.parse("{}")!!
        assertFalse(snap.on)
        assertEquals(TranslatePageSnapshot.STATUS_IDLE, snap.status)
        assertEquals(0, snap.translated)
        assertEquals(0, snap.candidates)
        assertEquals(0, snap.chars)
    }

    @Test
    fun `不认识的状态字回落到 idle`() {
        assertEquals(TranslatePageSnapshot.STATUS_IDLE, TranslatePageSnapshot.parse("""{"status":"天知道"}""")!!.status)
        assertEquals(TranslatePageSnapshot.STATUS_IDLE, TranslatePageSnapshot.parse("""{"status":123}""")!!.status)
    }

    @Test
    fun `负数夹到 0 且坏 JSON 返回 null`() {
        val snap = TranslatePageSnapshot.parse("""{"translated":-5,"candidates":-1,"chars":-9}""")!!
        assertEquals(0, snap.translated)
        assertEquals(0, snap.candidates)
        assertEquals(0, snap.chars)
        assertNull(TranslatePageSnapshot.parse("这不是 JSON"))
        assertNull(TranslatePageSnapshot.parse(""))
    }

    @Test
    fun `四种失败态都算 blocked`() {
        TranslatePageSnapshot.BLOCKED_STATUSES.forEach { status ->
            assertTrue(status, TranslatePageSnapshot.parse("""{"status":"$status"}""")!!.blocked)
        }
        assertFalse(TranslatePageSnapshot.parse("""{"status":"done"}""")!!.blocked)
    }

    // ── 命令 ──

    @Test
    fun `每个命令常量都被页面脚本处理`() {
        val js = script("03-boot.js")
        val commands = listOf(
            TranslatePageCommands.ON,
            TranslatePageCommands.OFF,
            TranslatePageCommands.DUAL,
            TranslatePageCommands.STYLE,
            TranslatePageCommands.TARGET,
            TranslatePageCommands.RETRY,
            TranslatePageCommands.SCAN_VISIBLE,
            TranslatePageCommands.SCAN_ALL,
            TranslatePageCommands.CLEAR,
        )
        commands.forEach { command ->
            assertTrue("页面脚本没有处理命令「$command」", js.contains("case '$command':"))
        }
    }

    @Test
    fun `命令是一行转义过的 JS`() {
        val call = TranslatePageCommands.js(TranslatePageCommands.STYLE, "a\"b")
        assertTrue(call.startsWith("window.__bbIT && window.__bbIT.command(\"style\", \""))
        assertTrue(call.endsWith("\")"))
        // 参数里的双引号必须被转义：否则会提前闭合字符串字面量（注入）
        assertTrue(call, call.contains("""a\"b"""))
        assertFalse("出现了未转义的收尾引号", call.contains("\"a\"b"))
    }

    @Test
    fun `命令缺省参数为空串`() {
        assertEquals(
            """window.__bbIT && window.__bbIT.command("retry", "")""",
            TranslatePageCommands.js(TranslatePageCommands.RETRY),
        )
    }
}
