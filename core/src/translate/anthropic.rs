//! Anthropic Messages 后端（`POST {base}/v1/messages`）。
//!
//! ## 三个独有要求（缺一个就直接 400）
//!
//! 1. **鉴权头是 `x-api-key`**，不是 `Authorization: Bearer`（Anthropic 两者都收，
//!    但原生写法是前者，中转网关通常只认它）；
//! 2. **`anthropic-version` 必填**（值见 [super::ANTHROPIC_VERSION]）；
//! 3. **`max_tokens` 必填**、**`system` 是顶层字段**（不是 `messages` 里的一条）。
//!    `max_tokens` 在本 App 里给 [MAX_TOKENS]：一段正文最多几百字，4k 足够，
//!    也不可能把「按 token 计费」账单顶爆。
//!
//! ## 响应
//!
//! `{"content":[{"type":"text","text":"…"},{"type":"thinking",…}]}` —— 只取 `type == "text"`
//! 的块并拼接；`thinking` 块（思考过程）**绝不能**混进译文。
//!
//! DeepSeek 官方也提供 Anthropic 兼容入口（`base_url = https://api.deepseek.com/anthropic`），
//! 所以这一档不止服务 `api.anthropic.com`：地址由用户填，端点拼接会把 `/v1/messages` 补在后面。

use crate::error::{CoreError, Result};
use serde_json::{json, Value};

/// 单次响应的最大 token 数（Anthropic 必填项）。
pub const MAX_TOKENS: u32 = 4096;

/// 组装请求体（`system` 在顶层，`messages` 只有用户一条）。
pub(crate) fn build_body(text: &str, from: &str, to: &str, model: &str) -> Value {
    json!({
        "model": model,
        "max_tokens": MAX_TOKENS,
        "system": super::prompt::system_prompt(from, to),
        "messages": [{ "role": "user", "content": text }],
    })
}

/// 翻译一段文本。
pub async fn translate(
    text: &str,
    from: &str,
    to: &str,
    options: &super::Options,
) -> Result<String> {
    let protocol = options.protocol();
    let key = options.api_key.trim();
    if key.is_empty() {
        return Err(CoreError::coded(
            "TRANSLATE_AUTH",
            format!("{} 认证失败（401）：尚未配置 API Key", protocol.label()),
        ));
    }
    let model = super::require_model(options)?;

    let url = options.chat_endpoint();
    let resp = super::json_request(protocol, &url, key)
        .json(&build_body(text, from, to, model))
        .send()
        .await?;

    let status = resp.status();
    let raw = resp.text().await?;
    if !status.is_success() {
        return Err(super::http_failure(protocol, status.as_u16(), &raw));
    }
    parse_response(&raw)
}

/// 解析响应：`content[]` 里只拼 `type == "text"` 的块。
pub(crate) fn parse_response(body: &str) -> Result<String> {
    let v: Value = serde_json::from_str(body)?;
    let mut out = String::new();
    if let Some(blocks) = v.get("content").and_then(|c| c.as_array()) {
        for block in blocks {
            if block.get("type").and_then(|t| t.as_str()) != Some("text") {
                continue;
            }
            if let Some(text) = block.get("text").and_then(|t| t.as_str()) {
                out.push_str(text);
            }
        }
    }

    let cleaned = super::prompt::clean_output(&out);
    if cleaned.is_empty() {
        return Err(CoreError::coded(
            "TRANSLATE_EMPTY",
            format!("Anthropic 返回空译文：{}", super::truncate(body, 200)),
        ));
    }
    Ok(cleaned)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn 请求体带_max_tokens_与顶层_system() {
        let body = build_body("Hello", "en", "zh-CN", "claude-x");
        assert_eq!(body["model"], "claude-x");
        assert_eq!(body["max_tokens"], MAX_TOKENS);
        // system 是顶层字段（Anthropic 的硬性格式），不在 messages 里
        assert!(body["system"].as_str().unwrap().contains("简体中文"));
        assert_eq!(body["messages"].as_array().unwrap().len(), 1);
        assert_eq!(body["messages"][0]["role"], "user");
        assert_eq!(body["messages"][0]["content"], "Hello");
    }

    #[test]
    fn 只取_text_块不把思考过程混进译文() {
        let body = r#"{"content":[
            {"type":"thinking","thinking":"用户想翻一段话…"},
            {"type":"text","text":"你好"},
            {"type":"text","text":"，世界"}
        ]}"#;
        assert_eq!(parse_response(body).unwrap(), "你好，世界");
        assert!(!parse_response(body).unwrap().contains("用户想翻"));
    }

    #[test]
    fn 空内容与坏_json_都算失败() {
        assert!(parse_response(r#"{"content":[]}"#).is_err());
        assert!(parse_response(r#"{"content":[{"type":"thinking","thinking":"x"}]}"#).is_err());
        assert!(parse_response("not json").is_err());
    }
}
