//! 沉浸式翻译的翻译后端（需求：把「原文 + 译文对照」塞进 App）。
//!
//! ## 两个后端，一个入口
//!
//! | 后端 | 需要 Key | 定位 |
//! |------|---------|------|
//! | [mymemory] | 否 | **默认**。匿名公开接口，无需任何配置，额度约 5000 词/天 |
//! | [deepseek] | 是（用户自带） | OpenAI 兼容的 `/chat/completions`，译文质量、术语一致性与长句处理明显更好，按 token 计费 |
//!
//! 选哪家由 [Options]（Kotlin 侧序列化成 JSON 传进来）决定，**本模块只做「建请求 + 解析响应」**；
//! 分片、去重、串行、重试与缓存都在 Kotlin 侧（`:translate` 模块），
//! 因为那些是「跨段落」的策略，只有上层才看得到全貌。
//!
//! ## 失败原因要能分类，所以这里不吞错误
//!
//! 上层（`TranslateEngine.classify`）按错误文本区分「Key 无效 / 余额不足 / 限流 / 网络 / 参数」，
//! 再决定「熔断、退避重试还是直接放弃」。因此这里的错误消息必须**带上状态码与语义关键词**
//! （例如 `DeepSeek 认证失败（401）：…`），不能只回一句「请求失败」。
//!
//! ## 为什么 DeepSeek 不用 `shared_http()`
//!
//! GitHub 那个共享客户端带 15s 总超时（防列表页卡死）。大模型一次请求十几秒是常态，
//! 15s 会把**正常翻译**掐成超时失败，于是这里单独维护一个超时更长的客户端（见 [http]）。

pub mod deepseek;
pub mod mymemory;

use crate::error::Result;
use serde::Deserialize;
use std::time::Duration;

/// DeepSeek 官方 OpenAI 兼容地址（用户可在设置里覆盖成代理/中转/其它兼容服务）。
pub const DEFAULT_DEEPSEEK_BASE: &str = "https://api.deepseek.com";

/// 翻译请求的总超时：大模型（尤其长段落）比普通 REST 慢一个量级。
const REQUEST_TIMEOUT: Duration = Duration::from_secs(60);

/// 翻译后端。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Provider {
    /// MyMemory 匿名接口（默认，零配置）
    MyMemory,
    /// DeepSeek（或任何 OpenAI 兼容服务），用户自带 API Key
    DeepSeek,
}

impl Provider {
    pub const DEFAULT: Provider = Provider::MyMemory;

    /// 从设置里的字符串还原后端（认不出来一律回落到默认，绝不 panic）。
    pub fn from_code(code: &str) -> Provider {
        match code.trim().to_ascii_lowercase().as_str() {
            "deepseek" | "llm" | "openai" | "compatible" => Provider::DeepSeek,
            _ => Provider::MyMemory,
        }
    }

    pub fn code(self) -> &'static str {
        match self {
            Provider::MyMemory => "mymemory",
            Provider::DeepSeek => "deepseek",
        }
    }
}

/// 一次翻译请求的选项（JSON 由 Kotlin 侧拼，字段名 camelCase）。
///
/// 全部字段都有默认值：解析失败或字段缺失时退化成「MyMemory + 默认地址」，
/// 保证老版本 App（不带这些字段）与手写配置都不会因为解析问题整段失败。
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Options {
    /// `mymemory` / `deepseek`
    pub provider: String,
    /// DeepSeek 的 API Key（仅内存中传递，不落日志）
    pub api_key: String,
    /// 模型名（空 = [deepseek::DEFAULT_MODEL]）
    pub model: String,
    /// 自定义接入地址（空 = [DEFAULT_DEEPSEEK_BASE]）
    pub base_url: String,
}

impl Options {
    /// 解析 Kotlin 传来的选项 JSON；坏 JSON 不报错，退化成默认选项。
    pub fn parse(json: &str) -> Options {
        serde_json::from_str(json).unwrap_or_default()
    }

    pub fn provider(&self) -> Provider {
        Provider::from_code(&self.provider)
    }

    /// DeepSeek 用的模型名（带默认值）。
    pub fn model_or_default(&self) -> &str {
        let m = self.model.trim();
        if m.is_empty() {
            deepseek::DEFAULT_MODEL
        } else {
            m
        }
    }

    /// 拼接 `/chat/completions`：
    /// - 空 → 官方地址；
    /// - 已含 `/chat/completions` → 原样（用户直接粘了完整端点）；
    /// - 其它 → 补一段（`https://api.deepseek.com/v1` 这类中转地址也能直接用）。
    pub fn chat_completions_url(&self) -> String {
        let base = self.base_url.trim().trim_end_matches('/');
        if base.is_empty() {
            format!("{DEFAULT_DEEPSEEK_BASE}/chat/completions")
        } else if base.ends_with("/chat/completions") {
            base.to_string()
        } else {
            format!("{base}/chat/completions")
        }
    }
}

/// 翻译一段文本。
///
/// * `from` / `to`：ISO 语言码（`en` / `zh-CN`）；
/// * `options`：后端与凭据（见 [Options]）；
/// * 返回译文；网络失败 / 服务返回错误 / 响应结构变化都返回 [CoreError]，
///   由上层决定「跳过这一段」还是「熔断整个会话」。
pub async fn translate(text: &str, from: &str, to: &str, options: &Options) -> Result<String> {
    let text = text.trim();
    if text.is_empty() {
        return Ok(String::new());
    }
    match options.provider() {
        Provider::MyMemory => mymemory::translate(text, from, to).await,
        Provider::DeepSeek => deepseek::translate(text, from, to, options).await,
    }
}

/// 翻译专用 HTTP 客户端（懒建的全局单例，连接池复用）。
///
/// 与 `api::client::shared_http()` 分开的原因见模块头注释：超时不同。
pub(crate) fn http() -> &'static reqwest::Client {
    use std::sync::OnceLock;
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .timeout(REQUEST_TIMEOUT)
            .connect_timeout(Duration::from_secs(10))
            .pool_max_idle_per_host(4)
            .tcp_nodelay(true)
            .build()
            .expect("构建翻译 HTTP 客户端失败")
    })
}

/// 从 OpenAI 兼容的错误响应体里取可读原因：`{"error":{"message":"…","type":"…","code":"…"}}`。
///
/// 取不到结构化错误时返回 None，调用方回落到「截断的原始响应体」。
pub(crate) fn extract_api_error(body: &str) -> Option<String> {
    let v: serde_json::Value = serde_json::from_str(body).ok()?;
    let err = v.get("error")?;
    // 有的服务把 error 直接写成字符串
    if let Some(s) = err.as_str() {
        let s = s.trim();
        return (!s.is_empty()).then(|| s.to_string());
    }
    let message = err.get("message").and_then(|m| m.as_str()).unwrap_or("").trim();
    let code = err.get("code").and_then(|c| c.as_str()).unwrap_or("").trim();
    let kind = err.get("type").and_then(|t| t.as_str()).unwrap_or("").trim();
    let mut parts: Vec<&str> = Vec::new();
    if !message.is_empty() {
        parts.push(message);
    }
    if !code.is_empty() {
        parts.push(code);
    }
    if !kind.is_empty() {
        parts.push(kind);
    }
    (!parts.is_empty()).then(|| parts.join(" / "))
}

/// 把任意响应体压成一行、限长，用于错误消息（避免把整页 HTML 塞进日志/页面提示）。
pub(crate) fn truncate(body: &str, max: usize) -> String {
    let one_line: String = body.split_whitespace().collect::<Vec<_>>().join(" ");
    if one_line.chars().count() <= max {
        one_line
    } else {
        let cut: String = one_line.chars().take(max).collect();
        format!("{cut}…")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn 认不出来的后端回落到默认() {
        assert_eq!(Provider::DEFAULT, Provider::MyMemory);
        assert_eq!(Provider::from_code("deepseek"), Provider::DeepSeek);
        assert_eq!(Provider::from_code(" DeepSeek "), Provider::DeepSeek);
        assert_eq!(Provider::from_code("openai"), Provider::DeepSeek);
        assert_eq!(Provider::from_code("mymemory"), Provider::MyMemory);
        assert_eq!(Provider::from_code(""), Provider::MyMemory);
        assert_eq!(Provider::from_code("天知道"), Provider::MyMemory);
    }

    #[test]
    fn 选项_json_缺失或损坏时退化为默认() {
        let o = Options::parse("{\"provider\":\"deepseek\",\"apiKey\":\"sk-x\",\"model\":\"\",\"baseUrl\":\"\"}");
        assert_eq!(o.provider(), Provider::DeepSeek);
        assert_eq!(o.api_key, "sk-x");
        assert_eq!(o.model_or_default(), deepseek::DEFAULT_MODEL);

        let broken = Options::parse("not json at all");
        assert_eq!(broken.provider(), Provider::MyMemory);
        assert!(broken.api_key.is_empty());

        // 老版本 App 只传空对象也不能崩
        assert_eq!(Options::parse("{}").provider(), Provider::MyMemory);
    }

    #[test]
    fn 端点拼接容忍三种写法() {
        let cases = [
            ("", "https://api.deepseek.com/chat/completions"),
            ("https://api.deepseek.com", "https://api.deepseek.com/chat/completions"),
            ("https://api.deepseek.com/", "https://api.deepseek.com/chat/completions"),
            ("https://api.deepseek.com/v1", "https://api.deepseek.com/v1/chat/completions"),
            ("https://relay.example.com/openai/v1/", "https://relay.example.com/openai/v1/chat/completions"),
            (
                "https://relay.example.com/v1/chat/completions",
                "https://relay.example.com/v1/chat/completions",
            ),
        ];
        for (base, want) in cases {
            let o = Options {
                base_url: base.to_string(),
                ..Default::default()
            };
            assert_eq!(o.chat_completions_url(), want, "base = {base}");
        }
    }

    #[test]
    fn 能读出_openai_兼容的错误体() {
        let body = r#"{"error":{"message":"Authentication Fails, Your api key is invalid","type":"authentication_error","code":"invalid_api_key"}}"#;
        let msg = extract_api_error(body).unwrap();
        assert!(msg.contains("Authentication Fails"));
        assert!(msg.contains("invalid_api_key"));

        assert_eq!(extract_api_error(r#"{"error":"boom"}"#).unwrap(), "boom");
        assert!(extract_api_error("<html>502</html>").is_none());
    }

    #[test]
    fn 响应体压成一行并截断() {
        assert_eq!(truncate("a\n\n  b\tc", 40), "a b c");
        assert_eq!(truncate(&"字".repeat(300), 10), format!("{}…", "字".repeat(10)));
    }
}
