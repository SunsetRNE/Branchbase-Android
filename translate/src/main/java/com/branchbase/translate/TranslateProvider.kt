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
 * | [DEEPSEEK] | 用户自带 | 按 token（官方/中转） | 长句、术语一致性要求高的技术文档 |
 *
 * 注意：[DEEPSEEK] 的模型名与接入地址都是**可配置**的，因为官方模型名会随版本调整
 * （`deepseek-chat` → `deepseek-flash` → …），而接口是 OpenAI 兼容的，
 * 用户也可能想接到中转 / 自建网关上。
 *
 * **这里只放与语言无关的 `code`**：名称与说明是界面文案（设置页的服务单选行），
 * 必须走 app 层的字符串资源，否则切到英文后这一行仍是中文（2026-11 文案重写时收口）。
 */
enum class TranslateProvider(val code: String) {

    MYMEMORY("mymemory"),
    DEEPSEEK("deepseek"),
    ;

    /** 是否需要用户填 Key（决定设置页要不要展开输入框）。 */
    val requiresKey: Boolean get() = this == DEEPSEEK

    companion object {

        val default: TranslateProvider = MYMEMORY

        fun byCode(code: String?): TranslateProvider =
            entries.firstOrNull { it.code.equals(code, true) } ?: default

        /** DeepSeek 默认接入地址（用户可在设置里改成中转/自建）。 */
        const val DEEPSEEK_BASE_URL: String = "https://api.deepseek.com"

        /**
         * DeepSeek 默认模型。
         *
         * 与 Rust 侧 `translate::deepseek::DEFAULT_MODEL` 保持一致：
         * 空模型名时由 Rust 兜底，这里填一份是为了设置页能显示「默认」提示。
         */
        const val DEEPSEEK_MODEL: String = "deepseek-flash"

        /** 设置页里的模型候选（也可以自己填，输入框不受限）。 */
        val MODEL_SUGGESTIONS: List<String> = listOf(DEEPSEEK_MODEL, "deepseek-v4-pro")
    }
}
