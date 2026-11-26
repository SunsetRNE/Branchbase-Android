//! OpenAI Responses 后端（`POST {base}/v1/responses`）。
//!
//! ## 与 Chat Completions 的三处差异（其余都一样）
//!
//! | | Chat Completions | Responses |
//! |---|---|---|
//! | 对话数组 | `messages` | `input`（内容块要带 `type: "input_text"`） |
//! | system | `messages[0].role = "system"` | 顶层 `instructions` |
//! | 取译文 | `choices[0].message.content` | `output[].content[]` 里 `type == "output_text"` 的 `text` |
//!
//! 模型列表与 Chat Completions **共用** `GET /v1/models`（同一个 Models API），
//! 所以设置页的「获取模型列表」在这一档不需要另写端点。

use crate::error::{CoreError, Result};
use serde_json::{json, Value};

/// 组装请求体（system 走顶层 `instructions`，用户内容走 `input` 的内容块）。
pub(crate) fn build_body(text: &str, from: &str, to: &str, model: &str) -> Value {
    json!({
        "model": model,
        "instructions": super::deepseek::system_prompt(from, to),
        "input": [{
            "role": "user",
            "content": [{ "type": "input_text", "text": text }],
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

/// 解析响应：优先顶层聚合字段 `output_text`（SDK 侧同款，部分网关直接给），
/// 否则遍历 `output[] → content[]` 取 `output_text` 块的 `text` 并拼接。
///
/// 模型偶尔会无视提示词加「译文：」前缀，这里复用 [super::deepseek::clean_output]：
/// 两档协议共用同一套清理规则，换协议不该让译文外壳的清理行为变掉。
pub(crate) fn parse_response(body: &str) -> Result<String> {
    let v: Value = serde_json::from_str(body)?;
    let mut out = String::new();

    if let Some(text) = v.get("output_text").and_then(|t| t.as_str()) {
        out.push_str(text);
    }
    if out.trim().is_empty() {
        if let Some(items) = v.get("output").and_then(|o| o.as_array()) {
            for item in items {
                let Some(content) = item.get("content").and_then(|c| c.as_array()) else {
                    continue;
                };
                for part in content {
                    if part.get("type").and_then(|t| t.as_str()) != Some("output_text") {
                        continue;
                    }
                    if let Some(text) = part.get("text").and_then(|t| t.as_str()) {
                        out.push_str(text);
                    }
                }
            }
        }
    }

    let cleaned = super::deepseek::clean_output(&out);
    if cleaned.is_empty() {
        return Err(CoreError::coded(
            "TRANSLATE_EMPTY",
            format!("OpenAI 返回空译文：{}", super::truncate(body, 200)),
        ));
    }
    Ok(cleaned)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn 请求体用_input_与_instructions() {
        let body = build_body("Hello world", "en", "zh-CN", "gpt-x");
        assert_eq!(body["model"], "gpt-x");
        // system 在顶层 instructions，不在 input 里
        assert!(body["instructions"].as_str().unwrap().contains("简体中文"));
        assert!(body["messages"].is_null());
        assert_eq!(body["input"][0]["role"], "user");
        assert_eq!(body["input"][0]["content"][0]["type"], "input_text");
        assert_eq!(body["input"][0]["content"][0]["text"], "Hello world");
    }

    #[test]
    fn 解析_output_里的_output_text() {
        let body = r#"{"output":[
            {"type":"reasoning","summary":[]},
            {"type":"message","role":"assistant","content":[
                {"type":"output_text","text":"你好，"},
                {"type":"output_text","text":"世界"}
            ]}
        ]}"#;
        assert_eq!(parse_response(body).unwrap(), "你好，世界");
    }

    #[test]
    fn 顶层_output_text_聚合字段优先() {
        let body = r#"{"output_text":"  你好  ","output":[]}"#;
        assert_eq!(parse_response(body).unwrap(), "你好");
    }

    #[test]
    fn 空输出与坏_json_都算失败() {
        assert!(parse_response(r#"{"output":[]}"#).is_err());
        assert!(parse_response(r#"{"output":[{"content":[{"type":"output_text","text":"   "}]}]}"#).is_err());
        assert!(parse_response("<html>502</html>").is_err());
    }
}
