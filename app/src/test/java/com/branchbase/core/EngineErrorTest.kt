package com.branchbase.core

import com.branchbase.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎错误解析的钉子（纯函数，不依赖 Android 运行时）。
 *
 * 它钉的是 `docs/specs/i18n-migration.md` §14 的**两条边界**：
 *
 * 1. **认码**：`ERROR:HTTP_404:…` 与还没换码的旧文案（`未知错误: HTTP 404 …`、`引擎不可用`）
 *    必须落到同一个 [EngineError.Coded] —— 迁移期双读靠的就是这一条，否则引擎与界面两边
 *    不同步的那个窗口里，界面会退回英文原话；
 * 2. **不猜**：没见过的码原样透出。猜错比不翻更坏（§9 那次「用文案猜语气」）。
 *
 * 断言只写**码 / 资源 ID / 参数**，不钉中文（§9：源码级测试钉字面量，每搬一次就假红一次）。
 */
class EngineErrorTest {

    @Test
    fun `没有错误与空错误是两件事`() {
        assertNull("null = 没有错误", parseEngineError(null))
        assertNull("空串 = 没有错误", parseEngineError("   "))
        assertNull("只有前缀也不算错误", parseEngineError("ERROR:"))
    }

    @Test
    fun `稳定码解析出码与详情`() {
        val e = parseEngineError("""ERROR:HTTP_404:{"message":"Not Found"}""") as EngineError.Coded
        assertEquals("HTTP_404", e.code)
        assertEquals(R.string.engine_error_http_404, e.res)
        assertEquals(listOf(404), e.args)
        assertEquals("""{"message":"Not Found"}""", e.detail)
    }

    @Test
    fun `没有 ERROR 前缀也能认_包装层会先剥掉它`() {
        // RustBridge.err() 的契约是「透出 ERROR: 之后的文本」，所以码可能在串首而不带前缀
        val e = parseEngineError("HTTP_429: {\"message\":\"API rate limit exceeded\"}") as EngineError.Coded
        assertEquals("HTTP_429", e.code)
        assertEquals(R.string.engine_error_http_429, e.res)
    }

    @Test
    fun `旧文案里的状态码同样能认_迁移期双读`() {
        // 真机日志的原文（1.0.64）：`事件源 /user/events 第 1 页失败：ERROR:未知错误: HTTP 404 Not Found`
        val notFound = parseEngineError("ERROR:未知错误: HTTP 404 Not Found") as EngineError.Coded
        assertEquals("HTTP_404", notFound.code)
        val unauthorized = parseEngineError("未知错误: HTTP 401 Unauthorized: {\"message\":\"Bad credentials\"}") as EngineError.Coded
        assertEquals("HTTP_401", unauthorized.code)
    }

    @Test
    fun `5xx 与其余状态码折成带参数的两条`() {
        val server = parseEngineError("HTTP_503: service unavailable") as EngineError.Coded
        assertEquals(R.string.engine_error_http_server, server.res)
        assertEquals(listOf(503), server.args)

        val teapot = parseEngineError("ERROR:HTTP_418:I'm a teapot") as EngineError.Coded
        assertEquals(R.string.engine_error_http, teapot.res)
        assertEquals(listOf(418), teapot.args)
    }

    @Test
    fun `引擎不可用两种写法都认_旧哨兵与新码`() {
        val legacy = parseEngineError("引擎不可用") as EngineError.Coded
        assertEquals(EngineCodes.ENGINE_UNAVAILABLE, legacy.code)
        assertEquals(R.string.engine_error_unavailable, legacy.res)

        val coded = parseEngineError("ERROR:ENGINE_UNAVAILABLE") as EngineError.Coded
        assertEquals(EngineCodes.ENGINE_UNAVAILABLE, coded.code)
    }

    @Test
    fun `没见过的码原样透出_不猜`() {
        val raw = parseEngineError("ERROR:NOT_GIT_REPO:不是 git 仓库") as EngineError.Raw
        assertTrue("未知码要原样带着，不能吞掉：${raw.text}", raw.text.contains("NOT_GIT_REPO"))
        assertTrue(raw.text.contains("不是 git 仓库"))
    }

    @Test
    fun `应用自己的说明句不会被当成引擎错误`() {
        // `repoAccessHint` 的说明句也是以「HTTP 404」开头的（全角冒号）。它本身是**结论**，
        // 不是引擎原文；被当成引擎错误就会被换成一句通用的 404，解释整段丢掉。
        val hint = "HTTP 404：GitHub 对无权限的私有仓库也返回 404 —— 可能是私有仓库"
        assertTrue(parseEngineError(hint) is EngineError.Raw)
        assertNull(engineErrorStatus(hint))
        // 旧文案里的「HTTP 404」出现在正文中间（不是状态码位置）时同样不认
        assertNull(engineErrorStatus("拉取失败：第 2 页返回 HTTP 404"))
    }

    @Test
    fun `普通文本按原文透出`() {
        val raw = parseEngineError("failed to lock file: .git/index.lock") as EngineError.Raw
        assertEquals("failed to lock file: .git/index.lock", raw.text)
        // 小写开头的串不能被当成码（码是全大写 ASCII）
        assertNull(raw.code)
    }

    @Test
    fun `状态码提取兼容新码与旧文案`() {
        assertEquals(404, engineErrorStatus("ERROR:HTTP_404: Not Found"))
        assertEquals(403, engineErrorStatus("未知错误: HTTP 403 Forbidden: {}"))
        assertNull("不是 HTTP 错误", engineErrorStatus("引擎不可用"))
        assertNull(engineErrorStatus(null))
    }
}
