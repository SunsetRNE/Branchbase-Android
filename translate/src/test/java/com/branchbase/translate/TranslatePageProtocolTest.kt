package com.branchbase.translate

import java.io.File
import org.json.JSONArray
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

    // ── 译文载荷（混合类型数组，1.0.89） ──

    /**
     * 一批产物的四种元素形态。
     *
     * `""`（判定跳过）与 `null`（翻译失败）**必须分得开**：页面靠「一批里一段都没插进去」
     * 判断服务不可用，而判定跳过也会「一段都没插进去」—— 混在一起时，
     * 一页全是中文的正文会被误报成翻译失败。
     */
    @Test
    fun `载荷元素区分跳过_整段_匹配与失败`() {
        val json = TranslatePagePayload.encode(
            listOf(
                ParagraphTranslation.None,
                ParagraphTranslation.Whole("你好，世界"),
                ParagraphTranslation.Matched(listOf(TranslatedPart("npm run dev", "运行开发"))),
                ParagraphTranslation.Failed,
            ),
        )
        val arr = JSONArray(json)
        assertEquals(4, arr.length())
        assertEquals("", arr.get(0))
        assertEquals("你好，世界", arr.get(1))

        val matched = arr.getJSONObject(2)
        assertEquals(TranslatePagePayload.MODE_MATCH, matched.getString("mode"))
        val pair = matched.getJSONArray("parts").getJSONObject(0)
        assertEquals("npm run dev", pair.getString("s"))
        assertEquals("运行开发", pair.getString("t"))
        assertTrue("失败元素必须是 JSON null", arr.isNull(3))
    }

    @Test
    fun `载荷里的引号与换行不会破坏_JSON`() {
        val text = "他说：\"你好\"\n第二行"
        val json = TranslatePagePayload.encode(listOf(ParagraphTranslation.Whole(text)))
        assertEquals(text, JSONArray(json).getString(0))
    }

    @Test
    fun `页面脚本认得出四种元素`() {
        val core = script("01-core.js")
        assertTrue("null 要按失败计", core.contains("tr === null"))
        assertTrue("对象要按匹配性译文渲染", core.contains("tr.parts"))
        assertTrue("判定跳过（空串）不能计失败", core.contains("if (!tr) continue;"))
        assertTrue("匹配性译文的渲染在 02-dom.js", script("02-dom.js").contains("data-bb-mode"))
    }
}
