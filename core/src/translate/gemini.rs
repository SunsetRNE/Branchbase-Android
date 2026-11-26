//! Gemini API 后端（`POST {base}/v1beta/models/{model}:generateContent`）。
//!
//! ## 与 OpenAI 系的四处差异
//!
//! | | OpenAI 系 | Gemini |
//! |---|---|---|
//! | 模型名 | 请求体 `model` | **URL 路径**（`…/models/{model}:generateContent`） |
//! | 鉴权 | `Authorization: Bearer` | `x-goog-api-key` 头（也接受 `?key=`，会进日志，不用） |
//! | 内容 | `messages[].content` | `contents[].parts[].text` |
//! | system | `messages` 里一条 / `instructions` | `systemInstruction.parts[].text` |
//!
//! ## 响应
//!
//! `{"candidates":[{"content":{"parts":[{"text":"…"}]}}]}` —— 只拼有 `text` 的 part；
//! **跳过 `thought: true` 的 part**（Gemini 2.5 的思考过程也放在 parts 里，
//! 混进译文就是「译文里突然多一段推理」）。
//!
//! 被安全策略拦下时没有 candidates，只有 `promptFeedback.blockReason`：这时给一句能懂的话，
//! 而不是「返回空译文」。

use crate::error::{CoreError, Result};
use serde_json::{json, Value};

/// 组装请求体（system 走 `systemInstruction`，用户内容走 `contents[0].parts[0].text`）。
pub(crate) fn build_body(text: &str, from: &str, to: &str) -> Value {
    json!({
        "systemInstruction": {
            "parts": [{ "text": super::prompt::system_prompt(from, to) }]
        },
        "contents": [{
            "role": "user",
            "parts": [{ "text": text }],
        }],
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
    // 模型名在 URL 里，空模型必须在这里拦住（否则拼出一个 `…/models/:generateContent` 的怪路径）
    super::require_model(options)?;

    let url = options.chat_endpoint();
    let resp = super::json_request(protocol, &url, key)
        .json(&build_body(text, from, to))
        .send()
        .await?;

    let status = resp.status();
    let raw = resp.text().await?;
    if !status.is_success() {
        return Err(super::http_failure(protocol, status.as_u16(), &raw));
    }
    parse_response(&raw)
}

/// 解析响应：拼 `candidates[0].content.parts[].text`，跳过思考过程（`thought: true`）。
pub(crate) fn parse_response(body: &str) -> Result<String> {
    let v: Value = serde_json::from_str(body)?;
    let mut out = String::new();
    if let Some(parts) = v
        .get("candidates")
        .and_then(|c| c.get(0))
        .and_then(|c| c.get("content"))
        .and_then(|c| c.get("parts"))
        .and_then(|p| p.as_array())
    {
        for part in parts {
            if part.get("thought").and_then(|t| t.as_bool()) == Some(true) {
                continue;
            }
            if let Some(text) = part.get("text").and_then(|t| t.as_str()) {
                out.push_str(text);
            }
        }
    }

    let cleaned = super::prompt::clean_output(&out);
    if cleaned.is_empty() {
        // 被安全策略拦截时给一句能懂的话（此时没有 candidates，只有 promptFeedback）
        if let Some(reason) = v
            .get("promptFeedback")
            .and_then(|f| f.get("blockReason"))
            .and_then(|r| r.as_str())
        {
            return Err(CoreError::coded(
                "TRANSLATE_EMPTY",
                format!("Gemini 拦截了这段内容（{reason}）"),
            ));
        }
        return Err(CoreError::coded(
            "TRANSLATE_EMPTY",
            format!("Gemini 返回空译文：{}", super::truncate(body, 200)),
        ));
    }
    Ok(cleaned)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn 请求体用_contents_与_system_instruction() {
        let body = build_body("Hello", "en", "zh-CN");
        assert!(body["systemInstruction"]["parts"][0]["text"]
            .as_str()
            .unwrap()
            .contains("简体中文"));
        assert_eq!(body["contents"][0]["role"], "user");
        assert_eq!(body["contents"][0]["parts"][0]["text"], "Hello");
        // 模型名不在请求体里（在 URL 里）
        assert!(body["model"].is_null());
    }

    #[test]
    fn 只拼_text_part_并跳过思考过程() {
        let body = r#"{"candidates":[{"content":{"parts":[
            {"text":"（思考：先看语法）","thought":true},
            {"text":"你好"},
            {"text":"，世界"}
        ]}}]}"#;
        assert_eq!(parse_response(body).unwrap(), "你好，世界");
    }

    #[test]
    fn 安全拦截给出可读原因() {
        let body = r#"{"promptFeedback":{"blockReason":"SAFETY"}}"#;
        let err = parse_response(body).unwrap_err().to_string();
        assert!(err.contains("拦截"), "{err}");
        assert!(err.contains("SAFETY"), "{err}");
    }

    #[test]
    fn 空候选与坏_json_都算失败() {
        assert!(parse_response(r#"{"candidates":[]}"#).is_err());
        assert!(parse_response(r#"not json"#).is_err());
    }
}
