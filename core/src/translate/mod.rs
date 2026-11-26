//! 沉浸式翻译的翻译后端（需求：把「原文 + 译文对照」塞进 App）。
//!
//! ## 两个后端，一个入口
//!
//! | 后端 | 需要 Key | 定位 |
//! |------|---------|------|
//! | [mymemory] | 否 | **默认**。匿名公开接口，无需任何配置，额度约 5000 词/天 |
//! | [openai_chat] / [responses] / [anthropic] / [gemini] | 是（用户自带） | 大模型，按 **协议**（端点方言）分派，译文质量、术语一致性与长句处理明显更好，按 token 计费 |
//!
//! 选哪家由 [Options]（Kotlin 侧序列化成 JSON 传进来）决定，**本模块只做「建请求 + 解析响应」**；
//! 分片、去重、串行、重试与缓存都在 Kotlin 侧（`:translate` 模块），
//! 因为那些是「跨段落」的策略，只有上层才看得到全貌。
//!
//! ## 协议（2026-11 · 第八轮）：同一个 Key，五种方言
//!
//! 自 1.4.51 起，`provider = deepseek` 之外还有一个 `protocol` 字段（见 [Protocol]）：
//! DeepSeek 官方 `/chat/completions`、OpenAI Chat Completions、OpenAI Responses、
//! Anthropic Messages、Gemini API。它决定**端点、鉴权头、请求体形状、响应体取译文的路径**四件事，
//! 而「用谁」仍是 [Provider]（MyMemory 与「自带 Key 的大模型」这两家）。
//!
//! 与 Kotlin 侧 `translate/src/main/java/com/branchbase/translate/TranslateProtocol.kt`
//! **必须手工保持一致**（同一张表写在两侧：Kotlin 要拼探测请求，Rust 要发翻译请求；
//! 两处都读 `.so` 之外的同一份约定）。改默认地址 / 默认模型 / 版本头时两边一起改。
//!
//! ## 失败原因要能分类，所以这里不吞错误
//!
//! 上层（`TranslateEngine.classify`）按**稳定码**（`TRANSLATE_AUTH` 等）优先分类，
//! 正文关键词（`认证失败` / `余额不足` / `限流` / `HTTP 5`）是迁移期的双读。
//! 因此这里的错误消息必须**带上状态码与语义关键词**，不能只回一句「请求失败」。
//!
//! ## 为什么不用 `shared_http()`
//!
//! GitHub 那个共享客户端带 15s 总超时（防列表页卡死）。大模型一次请求十几秒是常态，
//! 15s 会把**正常翻译**掐成超时失败，于是这里单独维护一个超时更长的客户端（见 [http]）。

pub mod anthropic;
pub mod gemini;
pub mod mymemory;
pub mod openai_chat;
pub mod prompt;
pub mod responses;

use crate::error::Result;
use serde::Deserialize;
use std::time::Duration;

/// OpenAI 官方地址。
pub const DEFAULT_OPENAI_BASE: &str = "https://api.openai.com";

/// Anthropic 官方地址。
pub const DEFAULT_ANTHROPIC_BASE: &str = "https://api.anthropic.com";

/// Gemini 官方地址。
pub const DEFAULT_GEMINI_BASE: &str = "https://generativelanguage.googleapis.com";

/// Anthropic 的必填版本头值。
///
/// 官方当前推荐更新的版本（2026-10-21），但中转 / 兼容网关（含 DeepSeek 的 `/anthropic`
/// 端点）对 `2023-06-01` 的接受度最高；这一项不是用户配置，是协议的组成部分。
pub const ANTHROPIC_VERSION: &str = "2023-06-01";

/// Gemini 路径里的模型占位符（模型名在 URL 里）。
pub const MODEL_PLACEHOLDER: &str = "{model}";

/// 翻译请求的总超时：大模型（尤其长段落）比普通 REST 慢一个量级。
const REQUEST_TIMEOUT: Duration = Duration::from_secs(60);

/// 翻译后端。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Provider {
    /// MyMemory 匿名接口（默认，零配置）
    MyMemory,
    /// 大模型（用户自带 API Key），具体方言看 [Protocol]
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

/// 端点方言：同一个「翻译这段文本」的意图，四种线上写法。
///
/// 码值与 Kotlin 侧 `TranslateProtocol.code` **逐字对应**（落盘键、options JSON 都用它）；
/// 改了就是破坏性变更。认不出来的码一律回落到 [Protocol::OpenAiChat]。
///
/// ## 厂商不在这一层（1.4.54）
///
/// DeepSeek 之前是一个协议，但它**没有私有报文**（官方文档把它列在「兼容协议」下）：
/// OpenAI Chat 兼容、OpenAI Responses 兼容、Anthropic 兼容三条通道，各自与对应的协议同形。
/// 现在「用哪家」由 `base_url` 决定（Kotlin 侧有地址列表），协议只描述「怎么说」。
/// 老配置里的 `protocol = "deepseek"` 在这里折成 [Protocol::OpenAiChat]（地址由 Kotlin 迁移）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Protocol {
    /// OpenAI Chat Completions：`POST {base}/v1/chat/completions`（覆盖面最广的一档）。
    OpenAiChat,
    /// OpenAI Responses：`POST {base}/v1/responses`（`input` 而不是 `messages`）。
    OpenAiResponses,
    /// Anthropic Messages：`POST {base}/v1/messages`（`x-api-key` + `anthropic-version`）。
    AnthropicMessages,
    /// Gemini：`POST {base}/v1beta/models/{model}:generateContent`（Key 走 `x-goog-api-key`）。
    Gemini,
}

impl Protocol {
    pub const DEFAULT: Protocol = Protocol::OpenAiChat;

    /// 从设置里的字符串还原协议（大小写 / 前后空格不敏感，认不出来回落默认）。
    ///
    /// 兼容别名：`deepseek`（1.4.53 及更早的唯一协议）、`openai`、`anthropic`、`google`。
    pub fn from_code(code: &str) -> Protocol {
        match code.trim().to_ascii_lowercase().as_str() {
            "openai-responses" | "responses" => Protocol::OpenAiResponses,
            "anthropic-messages" | "anthropic_messages" | "anthropic" | "messages" => {
                Protocol::AnthropicMessages
            }
            "gemini" | "google" | "gemini-api" => Protocol::Gemini,
            // openai-chat / openai / deepseek / "" / 脏值 都落在这里
            _ => Protocol::OpenAiChat,
        }
    }

    pub fn code(self) -> &'static str {
        match self {
            Protocol::OpenAiChat => "openai-chat",
            Protocol::OpenAiResponses => "openai-responses",
            Protocol::AnthropicMessages => "anthropic-messages",
            Protocol::Gemini => "gemini",
        }
    }

    /// 错误消息里的服务名（给人读的那半句）。
    pub fn label(self) -> &'static str {
        match self {
            Protocol::OpenAiChat | Protocol::OpenAiResponses => "OpenAI",
            Protocol::AnthropicMessages => "Anthropic",
            Protocol::Gemini => "Gemini",
        }
    }

    /// 接入地址留空时用的官方地址。
    pub fn default_base(self) -> &'static str {
        match self {
            Protocol::OpenAiChat | Protocol::OpenAiResponses => DEFAULT_OPENAI_BASE,
            Protocol::AnthropicMessages => DEFAULT_ANTHROPIC_BASE,
            Protocol::Gemini => DEFAULT_GEMINI_BASE,
        }
    }

    /// 翻译端点路径（Gemini 含 [MODEL_PLACEHOLDER]）。
    pub fn chat_path(self) -> &'static str {
        match self {
            Protocol::OpenAiChat => "/v1/chat/completions",
            Protocol::OpenAiResponses => "/v1/responses",
            Protocol::AnthropicMessages => "/v1/messages",
            Protocol::Gemini => "/v1beta/models/{model}:generateContent",
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
    /// 端点方言（见 [Protocol]；空 / 认不出来 = DeepSeek）
    pub protocol: String,
    /// 大模型的 API Key（仅内存中传递，不落日志）
    pub api_key: String,
    /// 模型名（空 = 该协议的默认值；没有默认值的协议会直接报配置错误）
    pub model: String,
    /// 自定义接入地址（空 = 该协议的官方地址）
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

    pub fn protocol(&self) -> Protocol {
        Protocol::from_code(&self.protocol)
    }

    /// 模型名（去空格；**没有内置默认** —— 空模型由 [require_model] 拦住）。
    pub fn model_or_default(&self) -> &str {
        self.model.trim()
    }

    /// 拼接翻译端点：空地址 → 该协议的官方地址；已含路径首段（`/v1`、`/v1beta`）不重复追加。
    pub fn chat_endpoint(&self) -> String {
        let protocol = self.protocol();
        let path = if protocol == Protocol::Gemini {
            protocol
                .chat_path()
                .replace(MODEL_PLACEHOLDER, self.model_or_default().trim())
        } else {
            protocol.chat_path().to_string()
        };
        join_endpoint(&self.base_url, &path, protocol.default_base())
    }
}

/// 拼端点，容忍用户把路径前缀也写进地址里（与 Kotlin 侧 `joinEndpoint` 同规则）。
///
/// 1. 空地址 → `official` + 路径；
/// 2. 没写协议 → 补 `https://`；
/// 3. 地址已以整条路径结尾 → 原样；
/// 4. 地址已含路径的第一段（`…/v1` + `/v1/chat/completions`）→ 只补剩余段；
/// 5. 其余 → 直接拼。
pub fn join_endpoint(base: &str, path: &str, official: &str) -> String {
    let raw = if base.trim().is_empty() {
        official
    } else {
        base.trim()
    };
    let with_scheme = if raw.starts_with("http://") || raw.starts_with("https://") {
        raw.to_string()
    } else {
        format!("https://{raw}")
    };
    let base = with_scheme.trim_end_matches('/').to_string();
    if base.ends_with(path) {
        return base;
    }
    let segments: Vec<&str> = path.trim_matches('/').split('/').collect();
    for take in (1..segments.len()).rev() {
        let prefix = segments[..take].join("/");
        if base.ends_with(&format!("/{prefix}")) {
            return format!("{base}/{}", segments[take..].join("/"));
        }
    }
    format!("{base}{path}")
}

/// 鉴权头（**自动补全的唯一真源**：不同方言连头名都不一样）。
///
/// 没 Key 时返回空表（调用方自己有更明确的「尚未配置 API Key」错误）。
pub fn auth_headers(protocol: Protocol, key: &str) -> Vec<(&'static str, String)> {
    let key = key.trim();
    if key.is_empty() {
        return Vec::new();
    }
    match protocol {
        Protocol::OpenAiChat | Protocol::OpenAiResponses => {
            vec![("Authorization", format!("Bearer {key}"))]
        }
        Protocol::AnthropicMessages => vec![
            ("x-api-key", key.to_string()),
            ("anthropic-version", ANTHROPIC_VERSION.to_string()),
        ],
        Protocol::Gemini => vec![("x-goog-api-key", key.to_string())],
    }
}

/// 建一个 JSON POST：`Accept` / `Content-Type` + 该协议的鉴权头。
///
/// 刻意**不补** `User-Agent` / `Content-Length` / `Accept-Encoding`：reqwest 自己会算，
/// 手写反而容易与服务端期望不一致。也不把 Key 放查询串（Gemini 的 `?key=` 会进 URL 与日志）。
pub(crate) fn json_request(protocol: Protocol, url: &str, key: &str) -> reqwest::RequestBuilder {
    let mut req = http()
        .post(url)
        .header("Accept", "application/json")
        .header("Content-Type", "application/json");
    for (name, value) in auth_headers(protocol, key) {
        req = req.header(name, value);
    }
    req
}

/// 模型为空时的统一错误（四档协议都要求显式模型，内置默认模型在 1.4.54 一并删掉了：
/// 默认模型属于「地址行」而不是协议，见 Kotlin 侧 `ApiAddress.defaultModel`）。
///
/// 归 `TRANSLATE_AUTH`：这不是「重试就会好」的错误，用户必须去设置里填一个模型。
/// 调度器对 AUTH 的处理正是「停发 + 提示去设置检查」，与这里要的行为一致。
pub(crate) fn require_model(options: &Options) -> Result<&str> {
    let model = options.model_or_default();
    if model.is_empty() {
        return Err(crate::error::CoreError::coded(
            "TRANSLATE_AUTH",
            format!(
                "{} 协议需要显式模型名：请在「设置 → 沉浸式翻译 → 模型」里填写，\
                 或点「获取模型列表」选一个",
                options.protocol().label()
            ),
        ));
    }
    Ok(model)
}

/// 翻译一段文本。
///
/// * `from` / `to`：ISO 语言码（`en` / `zh-CN`）；
/// * `options`：后端、协议与凭据（见 [Options]）；
/// * 返回译文；网络失败 / 服务返回错误 / 响应结构变化都返回 [CoreError]，
///   由上层决定「跳过这一段」还是「熔断整个会话」。
pub async fn translate(text: &str, from: &str, to: &str, options: &Options) -> Result<String> {
    let text = text.trim();
    if text.is_empty() {
        return Ok(String::new());
    }
    match options.provider() {
        Provider::MyMemory => mymemory::translate(text, from, to).await,
        Provider::DeepSeek => match options.protocol() {
            Protocol::OpenAiChat => openai_chat::translate(text, from, to, options).await,
            Protocol::OpenAiResponses => responses::translate(text, from, to, options).await,
            Protocol::AnthropicMessages => anthropic::translate(text, from, to, options).await,
            Protocol::Gemini => gemini::translate(text, from, to, options).await,
        },
    }
}

/// 翻译失败的稳定码：只编「上层要据此决定重试 / 熔断」的类别。
///
/// 与 `:translate` 的 `TranslateEngine.classify` 一一对应（码优先，正文关键词是迁移期的双读）：
/// 401 → AUTH；402 / 403 / 429 → QUOTA；5xx → NETWORK；其余 UNKNOWN。
/// 注意 403 归 **QUOTA**（MyMemory 的 403 就是 `ALL QUERIES EXHAUSTED`），不是 AUTH。
pub(crate) fn failure_code(status: u16) -> &'static str {
    match status {
        401 => "TRANSLATE_AUTH",
        402 | 403 | 429 => "TRANSLATE_QUOTA",
        500..=599 => "TRANSLATE_NETWORK",
        _ => "TRANSLATE_UNKNOWN",
    }
}

/// 把 HTTP 状态码翻成**能被人读懂、也能被 Kotlin 分类**的错误消息。
///
/// 关键词是契约：Kotlin 的 `TranslateEngine.classify` 靠「认证失败 / 余额 / 限流 / HTTP 5」
/// 决定熔断还是重试（见 `translate/src/main/java/.../TranslateEngine.kt`）。
pub(crate) fn error_message(protocol: Protocol, status: u16, detail: &str) -> String {
    let label = protocol.label();
    match status {
        401 => format!("{label} 认证失败（401）：{detail} —— 请在「设置 → 沉浸式翻译」检查 API Key"),
        402 => format!("{label} 余额不足（402）：{detail}"),
        429 => format!("{label} 限流（429）：{detail}"),
        400 | 422 => format!("{label} 请求参数无效（{status}）：{detail}"),
        _ => format!("{label} 接口返回 HTTP {status}：{detail}"),
    }
}

/// 非 2xx 的统一处理：取结构化错误原因（取不到就截断正文），再套稳定码与关键词。
pub(crate) fn http_failure(protocol: Protocol, status: u16, raw: &str) -> crate::error::CoreError {
    let detail = extract_api_error(raw).unwrap_or_else(|| truncate(raw, 200));
    crate::error::CoreError::coded(failure_code(status), error_message(protocol, status, &detail))
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

/// 从各家（结构相似的）错误响应体里取可读原因。
///
/// 取不到结构化错误时返回 None，调用方回落到「截断的原始响应体」。四种方言都落在 `error` 之下：
///
/// | 方言 | 形态 | 取到什么 |
/// |---|---|---|
/// | OpenAI / DeepSeek | `{"error":{"message","type","code"}}` | `message / code / type` |
/// | Anthropic | `{"type":"error","error":{"type","message"}}` | `message / type` |
/// | Gemini | `{"error":{"code":400,"message","status"}}` | `message / status`（`code` 是数字，跳过） |
///
/// `status` 只对 Gemini 有值，是它的语义码（`INVALID_ARGUMENT` / `RESOURCE_EXHAUSTED`），
/// 对用户比一个 400 更有用；其它家没有这个字段时行为不变。
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
    // Gemini 把语义码放在 `status`（`INVALID_ARGUMENT` / `RESOURCE_EXHAUSTED`），比数字 code 有用
    let status = err.get("status").and_then(|s| s.as_str()).unwrap_or("").trim();
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
    // 与 type 相同时不重复（有些网关把同一个词同时塞进 type 与 status）
    if !status.is_empty() && status != kind {
        parts.push(status);
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
    fn 协议码与_kotlin_侧逐字一致() {
        let codes = [
            (Protocol::OpenAiChat, "openai-chat"),
            (Protocol::OpenAiResponses, "openai-responses"),
            (Protocol::AnthropicMessages, "anthropic-messages"),
            (Protocol::Gemini, "gemini"),
        ];
        for (protocol, code) in codes {
            assert_eq!(protocol.code(), code);
            assert_eq!(Protocol::from_code(code), protocol);
            // 大小写 / 空格不敏感（手写配置、备份导入）
            assert_eq!(Protocol::from_code(&format!(" {} ", code.to_uppercase())), protocol);
        }
        // 1.4.53 及更早的 deepseek：协议折成 OpenAI Chat（DeepSeek 没有私有报文）
        assert_eq!(Protocol::from_code("deepseek"), Protocol::OpenAiChat);
        assert_eq!(Protocol::from_code(""), Protocol::DEFAULT);
        assert_eq!(Protocol::from_code("天知道"), Protocol::DEFAULT);
        assert_eq!(Protocol::DEFAULT, Protocol::OpenAiChat);
    }

    #[test]
    fn 选项_json_缺失或损坏时退化为默认() {
        let o = Options::parse(
            "{\"provider\":\"deepseek\",\"protocol\":\"anthropic-messages\",\"apiKey\":\"sk-x\",\"model\":\"claude-x\",\"baseUrl\":\"\"}",
        );
        assert_eq!(o.provider(), Provider::DeepSeek);
        assert_eq!(o.protocol(), Protocol::AnthropicMessages);
        assert_eq!(o.api_key, "sk-x");
        assert_eq!(o.chat_endpoint(), "https://api.anthropic.com/v1/messages");

        // 老版本 App 没有 protocol 字段 → OpenAI Chat；**没有默认模型**（空模型由 require_model 拦）
        let legacy = Options::parse("{\"provider\":\"deepseek\",\"apiKey\":\"sk-x\",\"model\":\"\",\"baseUrl\":\"\"}");
        assert_eq!(legacy.protocol(), Protocol::OpenAiChat);
        assert!(legacy.model_or_default().is_empty());

        let broken = Options::parse("not json at all");
        assert_eq!(broken.provider(), Provider::MyMemory);

        // 老版本 App 只传空对象也不能崩
        assert_eq!(Options::parse("{}").provider(), Provider::MyMemory);
    }

    #[test]
    fn 端点拼接容忍四种写法并按协议回落() {
        // OpenAI Chat：用户填的是通道根地址 / 带 /v1 / 完整端点
        let cases = [
            ("https://relay.example.com/openai/v1/", "https://relay.example.com/openai/v1/chat/completions"),
            (
                "https://relay.example.com/v1/chat/completions",
                "https://relay.example.com/v1/chat/completions",
            ),
            ("api.deepseek.com/v1", "https://api.deepseek.com/v1/chat/completions"),
        ];
        for (base, want) in cases {
            let o = Options {
                base_url: base.to_string(),
                ..Default::default()
            };
            assert_eq!(o.chat_endpoint(), want, "base = {base}");
        }

        // 空地址回落到**该协议自己的**官方地址
        let by_protocol = [
            (Protocol::OpenAiChat, "https://api.openai.com/v1/chat/completions"),
            (Protocol::OpenAiResponses, "https://api.openai.com/v1/responses"),
            (Protocol::AnthropicMessages, "https://api.anthropic.com/v1/messages"),
        ];
        for (protocol, want) in by_protocol {
            let o = Options {
                protocol: protocol.code().to_string(),
                ..Default::default()
            };
            assert_eq!(o.chat_endpoint(), want, "protocol = {:?}", protocol);
        }
    }

    #[test]
    fn 地址里已含路径首段时只补剩余段() {
        let o = Options {
            protocol: Protocol::OpenAiChat.code().to_string(),
            base_url: "https://api.openai.com/v1".to_string(),
            ..Default::default()
        };
        assert_eq!(o.chat_endpoint(), "https://api.openai.com/v1/chat/completions");

        // DeepSeek 的 Anthropic 兼容入口：base 带 /anthropic，路径仍是 /v1/messages
        let o = Options {
            protocol: Protocol::AnthropicMessages.code().to_string(),
            base_url: "https://api.deepseek.com/anthropic".to_string(),
            ..Default::default()
        };
        assert_eq!(o.chat_endpoint(), "https://api.deepseek.com/anthropic/v1/messages");
    }

    #[test]
    fn gemini_的模型名在路径里() {
        let o = Options {
            protocol: Protocol::Gemini.code().to_string(),
            model: "gemini-2.5-pro".to_string(),
            ..Default::default()
        };
        assert_eq!(
            o.chat_endpoint(),
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent"
        );
        // 只填域名也补 https
        let o = Options {
            protocol: Protocol::Gemini.code().to_string(),
            base_url: "generativelanguage.googleapis.com".to_string(),
            model: "gemini-2.5-flash".to_string(),
            ..Default::default()
        };
        assert_eq!(
            o.chat_endpoint(),
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
        );
    }

    #[test]
    fn 鉴权头按协议补且没_key_时不补() {
        assert_eq!(
            auth_headers(Protocol::OpenAiChat, "sk-1"),
            vec![("Authorization", "Bearer sk-1".to_string())]
        );
        assert_eq!(
            auth_headers(Protocol::OpenAiChat, "  sk-1\n"),
            vec![("Authorization", "Bearer sk-1".to_string())]
        );
        assert_eq!(
            auth_headers(Protocol::AnthropicMessages, "sk-ant"),
            vec![
                ("x-api-key", "sk-ant".to_string()),
                ("anthropic-version", ANTHROPIC_VERSION.to_string()),
            ]
        );
        assert_eq!(
            auth_headers(Protocol::Gemini, "g-1"),
            vec![("x-goog-api-key", "g-1".to_string())]
        );
        assert!(auth_headers(Protocol::Gemini, "   ").is_empty());
    }

    #[test]
    fn 空模型在所有协议下都给可分类的配置错误() {
        for protocol in [
            Protocol::OpenAiChat,
            Protocol::OpenAiResponses,
            Protocol::AnthropicMessages,
            Protocol::Gemini,
        ] {
            let o = Options {
                protocol: protocol.code().to_string(),
                ..Default::default()
            };
            let err = require_model(&o).unwrap_err();
            // 码由 JNI 边界拼进 `ERROR:<code>:<message>`；这里钉的是错误本身带的码与文案
            assert_eq!(err.code(), Some("TRANSLATE_AUTH"), "{protocol:?}: {err}");
            assert!(err.to_string().contains("模型"), "{protocol:?}: {err}");
        }
        // 填了就能过
        let o = Options {
            model: " any-model ".to_string(),
            ..Default::default()
        };
        assert_eq!(require_model(&o).unwrap(), "any-model");
    }

    #[test]
    fn 能读出_openai_兼容的错误体() {
        let body = r#"{"error":{"message":"Authentication Fails, Your api key is invalid","type":"authentication_error","code":"invalid_api_key"}}"#;
        let msg = extract_api_error(body).unwrap();
        assert!(msg.contains("Authentication Fails"));
        assert!(msg.contains("invalid_api_key"));

        assert_eq!(extract_api_error(r#"{"error":"boom"}"#).unwrap(), "boom");
        // Gemini：error.code 是数字，不该被当成字符串码拼进去；status 是它的语义码
        assert_eq!(
            extract_api_error(r#"{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}"#)
                .unwrap(),
            "API key not valid / INVALID_ARGUMENT"
        );
        assert!(extract_api_error("<html>502</html>").is_none());
    }

    #[test]
    fn 错误消息按协议带上服务名与状态码() {
        assert!(error_message(Protocol::AnthropicMessages, 401, "invalid x-api-key").contains("Anthropic 认证失败（401）"));
        assert!(error_message(Protocol::Gemini, 429, "quota").contains("Gemini 限流（429）"));
        assert!(error_message(Protocol::OpenAiResponses, 503, "overloaded").contains("HTTP 503"));
        // Kotlin 侧的分类关键词必须仍然在
        assert!(error_message(Protocol::OpenAiChat, 402, "Insufficient Balance").contains("余额不足"));
        assert!(error_message(Protocol::OpenAiChat, 400, "bad").contains("请求参数无效（400）"));
    }

    #[test]
    fn 响应体压成一行并截断() {
        assert_eq!(truncate("a\n\n  b\tc", 40), "a b c");
        assert_eq!(truncate(&"字".repeat(300), 10), format!("{}…", "字".repeat(10)));
    }
}
