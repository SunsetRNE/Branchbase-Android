package com.branchbase.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型列表探测的纯逻辑（URL 推导 / 响应解析 / 空 Key 短路）。
 *
 * 不测真实网络：那需要起一个 HTTP 服务，收益是「HttpURLConnection 会不会写错请求头」，
 * 而这条路径只有三行；真正会静默出错的是**端点拼接**与**响应结构**，两者都是纯函数。
 */
class DeepSeekModelsTest {

    @Test
    fun `空接入地址回落到官方端点`() {
        assertEquals("https://api.deepseek.com/models", DeepSeekModels.endpoint(""))
        assertEquals("https://api.deepseek.com/models", DeepSeekModels.endpoint("   "))
    }

    @Test
    fun `接入地址去掉末尾斜杠再拼 models`() {
        assertEquals("https://gw.example.com/v1/models", DeepSeekModels.endpoint("https://gw.example.com/v1/"))
    }

    @Test
    fun `只填域名时补 https`() {
        assertEquals("https://api.deepseek.com/models", DeepSeekModels.endpoint("api.deepseek.com"))
    }

    @Test
    fun `已经以 models 结尾不再重复追加`() {
        assertEquals("https://gw.example.com/models", DeepSeekModels.endpoint("https://gw.example.com/models"))
    }

    @Test
    fun `解析 data 里的 id 并去重排序`() {
        val json = """
            {"object":"list","data":[
              {"id":"deepseek-v4-pro"},
              {"id":"deepseek-flash"},
              {"id":"deepseek-flash"}
            ]}
        """.trimIndent()
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), DeepSeekModels.parse(json))
    }

    @Test
    fun `解析坏 JSON 返回空表`() {
        assertTrue(DeepSeekModels.parse("not json").isEmpty())
        assertTrue(DeepSeekModels.parse("""{"object":"list"}""").isEmpty())
    }

    @Test
    fun `未填 Key 不发起请求`() {
        val result = DeepSeekModels.fetch("https://gw.example.com/v1", "  ")
        assertTrue(result is DeepSeekModels.Result.Fail)
        assertEquals(DeepSeekModels.EMPTY_KEY, (result as DeepSeekModels.Result.Fail).detail)
    }
}
