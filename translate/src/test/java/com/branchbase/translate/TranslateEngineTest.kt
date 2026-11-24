package com.branchbase.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 失败分类单测。
 *
 * 分类决定「要不要熔断」，判错的代价很大：
 * 把网络抖动当额度用尽 → 明明还能翻却整个会话停摆；
 * 把额度用尽当网络抖动 → 一直重试、白烧剩余额度。
 */
class TranslateEngineTest {

    @Test
    fun `额度用尽与限流都归为 QUOTA`() {
        assertEquals(FailKind.QUOTA, TranslateEngine.classify("翻译服务返回 403：ALL QUERIES EXHAUSTED"))
        assertEquals(FailKind.QUOTA, TranslateEngine.classify("翻译服务 HTTP 429：Too Many Requests"))
        assertEquals(FailKind.QUOTA, TranslateEngine.classify("翻译额度已用尽"))
        // DeepSeek：402 余额不足 / 429 限流
        assertEquals(FailKind.QUOTA, TranslateEngine.classify("DeepSeek 余额不足（402）：Insufficient Balance"))
        assertEquals(FailKind.QUOTA, TranslateEngine.classify("DeepSeek 限流（429）：Rate Limit Reached"))
    }

    @Test
    fun `凭据问题归为 AUTH 而不是当成网络或参数`() {
        assertEquals(FailKind.AUTH, TranslateEngine.classify("尚未配置 API Key"))
        assertEquals(
            FailKind.AUTH,
            TranslateEngine.classify(
                "DeepSeek 认证失败（401）：Authentication Fails, Your api key is invalid —— 请在「设置 → 沉浸式翻译」检查 API Key",
            ),
        )
        assertEquals(FailKind.AUTH, TranslateEngine.classify("401 Unauthorized"))
    }

    @Test
    fun `认证类必须排在通用 INVALID 之前`() {
        // 同一条消息里既有 INVALID 又是 Key 的问题：要的是「去改 Key」，不是「换语言对重试」
        assertEquals(FailKind.AUTH, TranslateEngine.classify("INVALID API KEY"))
    }

    @Test
    fun `网络类失败归为 NETWORK`() {
        assertEquals(FailKind.NETWORK, TranslateEngine.classify("HTTP 请求失败: error sending request: timeout"))
        assertEquals(FailKind.NETWORK, TranslateEngine.classify("翻译服务 HTTP 502：bad gateway"))
        assertEquals(FailKind.NETWORK, TranslateEngine.classify("连接超时"))
        assertEquals(FailKind.NETWORK, TranslateEngine.classify("DeepSeek 接口返回 HTTP 503：server overloaded"))
    }

    @Test
    fun `参数问题归为 UNSUPPORTED`() {
        assertEquals(FailKind.UNSUPPORTED, TranslateEngine.classify("翻译服务返回 200：INVALID LANGUAGE PAIR"))
        assertEquals(FailKind.UNSUPPORTED, TranslateEngine.classify("该语言对不支持"))
    }

    @Test
    fun `分片失效的告警不能被当成额度问题`() {
        // QUERY LENGTH LIMIT 里含 LIMIT，但它说明的是**我们自己分片失败**，
        // 熔断会掩盖这个 bug，所以必须归到 UNKNOWN
        assertEquals(
            FailKind.UNKNOWN,
            TranslateEngine.classify("QUERY LENGTH LIMIT DONE. MAX ALLOWED QUERY : 500 CHARS"),
        )
    }

    @Test
    fun `认不出来的错误不熔断`() {
        assertEquals(FailKind.UNKNOWN, TranslateEngine.classify("翻译服务返回空结果"))
    }

    // ── 1.4.24：稳定码优先（迁移期正文兜底） ──

    @Test
    fun `稳定码映射到失败类别`() {
        assertEquals(FailKind.AUTH, TranslateEngine.classify("TRANSLATE_AUTH:DeepSeek 认证失败（401）：…"))
        assertEquals(FailKind.QUOTA, TranslateEngine.classify("TRANSLATE_QUOTA:翻译服务返回 403：ALL QUERIES EXHAUSTED"))
        assertEquals(FailKind.NETWORK, TranslateEngine.classify("TRANSLATE_NETWORK:DeepSeek 接口返回 HTTP 503"))
        assertEquals(FailKind.UNSUPPORTED, TranslateEngine.classify("TRANSLATE_UNSUPPORTED:INVALID LANGPAIR"))
        // 空译文 / 分片超限都归 UNKNOWN（不重试、不熔断 —— 后者是我们自己的 bug）
        assertEquals(FailKind.UNKNOWN, TranslateEngine.classify("TRANSLATE_EMPTY:DeepSeek 返回空译文"))
        assertEquals(FailKind.UNKNOWN, TranslateEngine.classify("TRANSLATE_QUERY_LIMIT:QUERY LENGTH LIMIT DONE"))
    }

    @Test
    fun `拆码只认串首的大写码`() {
        assertEquals("TRANSLATE_AUTH" to "正文", TranslateEngine.splitErrorCode("TRANSLATE_AUTH:正文"))
        assertEquals(null to "DeepSeek 认证失败（401）：未配置", TranslateEngine.splitErrorCode("DeepSeek 认证失败（401）：未配置"))
        // 正文里的冒号不参与切割
        assertEquals("TRANSLATE_QUOTA" to "a:b:c", TranslateEngine.splitErrorCode("TRANSLATE_QUOTA:a:b:c"))
    }
}
