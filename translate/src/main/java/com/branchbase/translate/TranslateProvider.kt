package com.branchbase.translate

/**
 * 可选的翻译后端。
 *
 * 一个「零配置但质量一般」+ 一个「要自己填 Key 但质量更好」的组合，是这类功能的常规做法
 * （网页版沉浸式翻译也是同一思路：内置免费引擎，同时允许填自己的 Key 接 DeepL / OpenAI）。
 *
 * | 后端 | Key | 计费 | 适用 |
 * |------|-----|------|------|
 * | [MYMEMORY] | 不需要 | 匿名额度约 5000 词/天 | 默认；随手看两眼够了 |
 * | [EXTERNAL] | 用户自备 | 按 token（官方/中转） | 长句、术语一致性要求高的技术文档 |
 *
 * ## 1.4.54 起 [EXTERNAL] 是「外部模型接入」，不再是 DeepSeek
 *
 * 以前这一档写死 DeepSeek（模型候选、默认地址、错误文案全带 DeepSeek）。现在它只是
 * 「用户自己的大模型服务」这个**入口**：具体用哪家、哪种协议，由设置页里的
 * **协议**（[TranslateProtocol]）与**接口地址**（[ApiAddress]）两个字段决定。
 * DeepSeek 降级成地址列表里的一行，不再有专属代码路径。
 *
 * `code` 仍是 `"deepseek"`：它是落盘键（`translate.provider`）的一部分，改了老配置就读不回来。
 *
 * **这里只放与语言无关的 `code`**：名称与说明是界面文案（设置页的服务单选行），
 * 必须走 app 层的字符串资源，否则切到英文后这一行仍是中文（2026-11 文案重写时收口）。
 */
enum class TranslateProvider(val code: String) {

    MYMEMORY("mymemory"),

    /** 外部模型接入（协议 + 地址 + Key 由用户配）。 */
    EXTERNAL("deepseek"),
    ;

    /** 是否需要用户填 Key（决定设置页要不要展开输入框）。 */
    val requiresKey: Boolean get() = this == EXTERNAL

    companion object {

        val default: TranslateProvider = MYMEMORY

        fun byCode(code: String?): TranslateProvider {
            val c = code?.trim()?.lowercase().orEmpty()
            // 1.4.53 及更早把它叫 deepseek；两套写法都认，落盘键保持不变
            return entries.firstOrNull { it.code == c } ?: default
        }
    }
}
