//! MyMemory 后端（默认，零配置）。
//!
//! ## 为什么默认是 MyMemory
//!
//! 实测（本机直连）：
//! - Google 的非官方 `translate_a/single`（gtx）→ **HTTP 429**，被限流/不可达；
//! - LibreTranslate 的公共实例 → 连接超时（多数已停止公开服务或需要 key）；
//! - **MyMemory**（`api.mymemory.translated.net`）→ 200，无需 API key，匿名额度约 5000 词/天。
//!
//! 想要更好的译文质量（长句、术语一致性）时，用户可以在设置里切到
//! [super::deepseek] 并填入自己的 API Key。
//!
//! ## 约束（由上层负责，不在这里做）
//!
//! MyMemory 单次 `q` 上限 500 字符，且是「一次请求一段」。分片、去重、串行与缓存
//! 都在 Kotlin 侧（`:translate` 模块的 `Translator.kt` / `TranslateScheduler.kt`）
//! ——放上层才能跨页面复用缓存，也避免这里为「一批文本」引入并发与重试策略。

use crate::error::{CoreError, Result};

/// 翻译端点（可替换）。
const ENDPOINT: &str = "https://api.mymemory.translated.net/get";

/// 翻译一段文本。
///
/// * `from` / `to`：ISO 语言码（如 `en` / `zh-CN`）。MyMemory 用 `langpair=en|zh-CN`。
/// * 返回译文；网络失败 / 服务返回错误 / 响应结构变化都返回 [CoreError]，
///   由上层决定「跳过这一段」还是「熔断整个会话」。
pub async fn translate(text: &str, from: &str, to: &str) -> Result<String> {
    let text = text.trim();
    if text.is_empty() {
        return Ok(String::new());
    }

    let resp = super::http()
        .get(ENDPOINT)
        .query(&[
            ("q", text),
            ("langpair", &format!("{from}|{to}")),
            // 关闭 MyMemory 的「匹配优先」，尽量走机器翻译（否则会给近似句子库结果）
            ("mt", "1"),
        ])
        .send()
        .await?;

    let status = resp.status();
    let body = resp.text().await?;
    if !status.is_success() {
        return Err(CoreError::Other(format!(
            "翻译服务 HTTP {}：{}",
            status.as_u16(),
            super::truncate(&body, 120)
        )));
    }
    parse_response(&body)
}

/// 解析 MyMemory 响应：`{"responseData":{"translatedText":"…"},"responseStatus":200}`
///
/// 两种「HTTP 200 但没结果」的情况都要当成失败，否则页面会插入一段错误提示当译文：
/// - `responseStatus` 非 200（额度用尽 / 语言对不支持）；
/// - `translatedText` 是服务端的告警文本（如 `QUERY LENGTH LIMIT DONE...`）。
pub(crate) fn parse_response(body: &str) -> Result<String> {
    let v: serde_json::Value = serde_json::from_str(body)?;
    let status = v.get("responseStatus").and_then(|x| x.as_i64()).unwrap_or(200);
    if status != 200 {
        let detail = v.get("responseDetails").and_then(|x| x.as_str()).unwrap_or("");
        return Err(CoreError::Other(format!("翻译服务返回 {status}：{detail}")));
    }
    let text = v
        .get("responseData")
        .and_then(|d| d.get("translatedText"))
        .and_then(|t| t.as_str())
        .unwrap_or("")
        .trim()
        .to_string();
    if text.is_empty() {
        return Err(CoreError::Other("翻译服务返回空结果".into()));
    }
    if text.starts_with("QUERY LENGTH LIMIT") || text.starts_with("INVALID") {
        return Err(CoreError::Other(text));
    }
    Ok(text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn 解析正常响应() {
        let body = r#"{"responseData":{"translatedText":"你好世界"},"responseStatus":200}"#;
        assert_eq!(parse_response(body).unwrap(), "你好世界");
    }

    #[test]
    fn 额度用尽或语言对不支持时算失败() {
        let body = r#"{"responseData":null,"responseStatus":403,"responseDetails":"ALL QUERIES EXHAUSTED"}"#;
        assert!(parse_response(body).is_err());
    }

    #[test]
    fn 把服务端告警文本当成失败而不是译文() {
        let body = r#"{"responseData":{"translatedText":"QUERY LENGTH LIMIT DONE. MAX ALLOWED QUERY : 500 CHARS"},"responseStatus":200}"#;
        assert!(parse_response(body).is_err());
    }

    #[test]
    fn 空结果算失败() {
        let body = r#"{"responseData":{"translatedText":"   "},"responseStatus":200}"#;
        assert!(parse_response(body).is_err());
    }
}
