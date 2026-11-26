//! OpenAI Chat Completions 协议：`POST {base}/v1/chat/completions`。
//!
//! ## 一档协议，很多厂商
//!
//! 这一档也是**覆盖面最广**的一档：任何「OpenAI 兼容」的厂商、中转、自建网关
//! （含 DeepSeek 的 `https://api.deepseek.com/v1`）都能用，因为路径与载荷与 OpenAI 完全一致。
//! 所以这里**没有任何厂商特有的分支** —— 厂商差异只体现在「地址」与「模型名」上，
//! 由设置页的地址列表决定，代码不认厂商。
//!
//! ## 请求与响应的形状
//!
//! 请求：`{model, messages:[system,user], stream:false}`；
//! 响应：`{"choices":[{"message":{"content":"…"}}]}`。
//!
//! ## 三个刻意的取舍
//!
//! 1. **模型名不写死**：官方模型名会随版本调整，这里没有默认模型；空模型在
//!    [super::require_model] 就被拦住并提示去设置里填，不会发出一个必然 400 的请求；
//! 2. **不传 temperature**：各家对「翻译任务推荐温度」的说法不一样，与其钉一个可能被
//!    弃用的取值，不如用服务端默认（要调也只改请求体这一行）；
//! 3. **一次请求一段**：本 App 的正文段落很短，拼批反而会让模型串味、也让失败定位更难；
//!    批量留给上层调度器（它已经做了全局串行与退避）。

use crate::error::{CoreError, Result};

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
        // 上层其实已经拦过一次（引擎里短路），这里是兜底：直接说清是「没配 Key」
        return Err(CoreError::coded(
            "TRANSLATE_AUTH",
            format!("{} 认证失败（401）：尚未配置 API Key", protocol.label()),
        ));
    }
    let model = super::require_model(options)?;

    let body = serde_json::json!({
        "model": model,
        "messages": super::prompt::build_messages(text, from, to),
        "stream": false,
    });

    let url = options.chat_endpoint();
    let resp = super::json_request(protocol, &url, key)
        .json(&body)
        .send()
        .await?;

    let status = resp.status();
    let raw = resp.text().await?;
    if !status.is_success() {
        return Err(super::http_failure(protocol, status.as_u16(), &raw));
    }
    parse_response(&raw)
}

/// 解析响应：`{"choices":[{"message":{"content":"…"}}]}`。
///
/// 模型偶尔会无视提示词加「译文：」外壳或整体引号，清理规则在 [super::prompt::clean_output]，
/// 四档协议共用 —— 换协议不该让译文的清理行为变掉。
pub(crate) fn parse_response(body: &str) -> Result<String> {
    let v: serde_json::Value = serde_json::from_str(body)?;
    let content = v
        .get("choices")
        .and_then(|c| c.get(0))
        .and_then(|c| c.get("message"))
        .and_then(|m| m.get("content"))
        .and_then(|c| c.as_str())
        .unwrap_or("");
    let cleaned = super::prompt::clean_output(content);
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
    fn 解析正常响应并清理外壳() {
        let body = r#"{"choices":[{"message":{"role":"assistant","content":"  你好，世界  "}}]}"#;
        assert_eq!(parse_response(body).unwrap(), "你好，世界");
    }

    #[test]
    fn 空译文与结构异常都算失败() {
        assert!(parse_response(r#"{"choices":[]}"#).is_err());
        assert!(parse_response(r#"{"choices":[{"message":{"content":"   "}}]}"#).is_err());
        assert!(parse_response("<html>502</html>").is_err());
    }

    #[test]
    fn 状态码映射出可分类的错误消息() {
        // 消息拼装在 mod.rs（按协议带服务名）；这里钉的是**分类关键词**没被改掉
        let msg = |status, detail| super::super::error_message(super::super::Protocol::OpenAiChat, status, detail);
        assert!(msg(401, "Authentication Fails").contains("认证失败"));
        assert!(msg(402, "Insufficient Balance").contains("余额不足"));
        assert!(msg(429, "Rate Limit Reached").contains("限流"));
        assert!(msg(503, "server overloaded").contains("HTTP 503"));
    }
}
