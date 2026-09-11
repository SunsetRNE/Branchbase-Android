//! DeepSeek 后端（OpenAI 兼容 `/chat/completions`，用户自带 API Key）。
//!
//! ## 为什么值得加这一家
//!
//! MyMemory 这类免费句子库接口在**长句、技术术语、中英混排**上明显吃亏（它本质是找近似句），
//! 而大模型能在译文里保持术语一致、按上下文断句。代价是「需要用户自己的 Key 且按 token 计费」，
//! 所以它做成**可选后端**：默认仍是不用配置的 MyMemory，愿意填 Key 的人再切过来。
//!
//! 官方文档：<https://api-docs.deepseek.com/zh-cn/>（`base_url = https://api.deepseek.com`，
//! `Authorization: Bearer <key>`，请求体与 OpenAI 的 `chat/completions` 一致）。
//! 因为接口是 OpenAI 兼容的，`base_url` 一旦可配，**任何兼容服务（中转、Azure、自建网关）
//! 都能直接用**——这也是把 base_url 暴露给用户的原因。
//!
//! ## 三个刻意的取舍
//!
//! 1. **模型名不写死**：官方模型名会随版本调整（`deepseek-chat` → `deepseek-flash` → …），
//!    这里只给一个默认值，设置页可以改，避免升级模型时 App 失效；
//! 2. **不传 temperature**：官方对「翻译任务推荐温度」的说法在版本间变过，
//!    与其钉死一个可能被弃用的取值，不如用服务端默认（要调也只改请求体这一行）；
//! 3. **一次请求一段**：本 App 的正文段落很短，拼批反而会让模型串味、也让失败定位更难；
//!    批量留给上层调度器（它已经做了全局串行与退避）。
//!
//! ## 提示词为什么强调占位符
//!
//! Kotlin 侧在翻译前会把 URL / `@提及` / `#编号` / 提交 SHA 换成 `⟦n⟧`（见 `PlaceholderGuard`）。
//! 模型**必须原样保留**这些标记，否则还原会失败、整段退化成「不加保护再翻一次」。
//! 这是本项目里「提示词」唯一真正承担职责的地方，所以在 system 里写成硬性要求。

use crate::error::{CoreError, Result};

/// 默认模型（可用设置页覆盖）。
pub const DEFAULT_MODEL: &str = "deepseek-flash";

/// 翻译一段文本。
pub async fn translate(
    text: &str,
    from: &str,
    to: &str,
    options: &super::Options,
) -> Result<String> {
    let key = options.api_key.trim();
    if key.is_empty() {
        // 上层其实已经拦过一次（引擎里短路），这里是兜底：直接说清是「没配 Key」
        return Err(CoreError::Other(
            "DeepSeek 认证失败（401）：尚未配置 API Key".into(),
        ));
    }

    let body = serde_json::json!({
        "model": options.model_or_default(),
        "messages": build_messages(text, from, to),
        "stream": false,
    });

    let resp = super::http()
        .post(options.chat_completions_url())
        .bearer_auth(key)
        .json(&body)
        .send()
        .await?;

    let status = resp.status();
    let raw = resp.text().await?;
    if !status.is_success() {
        let detail = super::extract_api_error(&raw)
            .unwrap_or_else(|| super::truncate(&raw, 200));
        return Err(CoreError::Other(error_message(status.as_u16(), &detail)));
    }
    parse_response(&raw)
}

/// 组装对话消息（system 里是硬性要求，user 里只放原文）。
pub fn build_messages(text: &str, from: &str, to: &str) -> Vec<serde_json::Value> {
    vec![
        serde_json::json!({"role": "system", "content": system_prompt(from, to)}),
        serde_json::json!({"role": "user", "content": text}),
    ]
}

/// 翻译用的系统提示词。
pub fn system_prompt(from: &str, to: &str) -> String {
    format!(
        "你是专业的技术文档翻译引擎。把用户给出的文本翻译成{}。\n\
         硬性要求：\n\
         1. 只输出译文本身：不要解释、不要前后缀、不要用引号包裹、不要输出「译文：」之类的标记；\n\
         2. 形如 ⟦0⟧ ⟦1⟧ 的占位符必须**原样保留**，数量与顺序都不能变\
         （它们代表链接、用户名、编号、提交哈希等，不能翻译、不能改写、不能删除）；\n\
         3. 保留 Markdown 标记、代码标识符、命令、路径、版本号、数字与单位的原始写法；\n\
         4. 保持与原文一致的换行与段落结构。\n\
         原文语言：{}。",
        target_label(to),
        source_label(from),
    )
}

/// 解析响应：`{"choices":[{"message":{"content":"…"}}]}`
pub(crate) fn parse_response(body: &str) -> Result<String> {
    let v: serde_json::Value = serde_json::from_str(body)?;
    let content = v
        .get("choices")
        .and_then(|c| c.get(0))
        .and_then(|c| c.get("message"))
        .and_then(|m| m.get("content"))
        .and_then(|c| c.as_str())
        .unwrap_or("");
    let cleaned = clean_output(content);
    if cleaned.is_empty() {
        return Err(CoreError::Other(format!(
            "DeepSeek 返回空译文：{}",
            super::truncate(body, 200)
        )));
    }
    Ok(cleaned)
}

/// 清理模型输出：去掉「译文：」前缀与整体包裹的引号。
///
/// 模型偶尔会无视提示词加这些壳。**只处理「整体包裹」的情况**：
/// 引号出现在句中（例如译文本就带引号）一律不动，避免误伤内容。
pub fn clean_output(raw: &str) -> String {
    let mut s = raw.trim();
    for prefix in [
        "译文：", "翻译：", "译文:", "翻译:", "Translation:", "Translation：", "Output:", "输出：",
    ] {
        if let Some(rest) = s.strip_prefix(prefix) {
            s = rest.trim_start();
            break;
        }
    }
    strip_wrapping_quotes(s).trim().to_string()
}

fn strip_wrapping_quotes(s: &str) -> &str {
    // （开引号, 闭引号）；左右引号不同，避免用同一个 ASCII 引号两次造成误判
    let pairs = [('"', '"'), ('“', '”'), ('「', '」')];
    for (open, close) in pairs {
        if !s.starts_with(open) || !s.ends_with(close) || s.chars().count() < 2 {
            continue;
        }
        let inner = &s[open.len_utf8()..s.len() - close.len_utf8()];
        if !inner.contains(open) && !inner.contains(close) {
            return inner;
        }
    }
    s
}

/// 把 HTTP 状态码翻成**能被人读懂、也能被 Kotlin 分类**的错误消息。
///
/// 关键词是契约：Kotlin 的 `TranslateEngine.classify` 靠「认证失败 / 余额 / 限流 / HTTP 5」
/// 决定熔断还是重试（见 `translate/src/main/java/.../TranslateEngine.kt`）。
fn error_message(status: u16, detail: &str) -> String {
    match status {
        401 => format!("DeepSeek 认证失败（401）：{detail} —— 请在「设置 → 沉浸式翻译」检查 API Key"),
        402 => format!("DeepSeek 余额不足（402）：{detail}"),
        429 => format!("DeepSeek 限流（429）：{detail}"),
        400 | 422 => format!("DeepSeek 请求参数无效（{status}）：{detail}"),
        _ => format!("DeepSeek 接口返回 HTTP {status}：{detail}"),
    }
}

fn target_label(to: &str) -> String {
    match to.trim().to_ascii_lowercase().as_str() {
        "zh" | "zh-cn" | "zh-hans" => "简体中文".into(),
        "zh-tw" | "zh-hant" => "繁体中文".into(),
        "en" => "英语".into(),
        other => other.to_string(),
    }
}

fn source_label(from: &str) -> String {
    match from.trim().to_ascii_lowercase().as_str() {
        "zh" | "zh-cn" | "zh-hans" => "简体中文".into(),
        "en" => "英语".into(),
        "" => "自动判断".into(),
        other => other.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn 提示词带上目标语言与占位符约束() {
        let p = system_prompt("en", "zh-CN");
        assert!(p.contains("简体中文"));
        assert!(p.contains("英语"));
        // 占位符约束是提示词里唯一真正承担职责的一条，不能被删掉
        assert!(p.contains("⟦0⟧") && p.contains("原样保留"));
        assert!(p.contains("不要用引号包裹"));

        assert!(system_prompt("zh-CN", "en").contains("英语"));
        assert!(system_prompt("", "").contains("自动判断"));
        // 未知语言码原样带过去，不做瞎猜
        assert!(system_prompt("ja", "ko").contains("ko"));
    }

    #[test]
    fn 消息结构是_系统加用户两条() {
        let msgs = build_messages("Hello world", "en", "zh-CN");
        assert_eq!(msgs.len(), 2);
        assert_eq!(msgs[0]["role"], "system");
        assert_eq!(msgs[1]["role"], "user");
        assert_eq!(msgs[1]["content"], "Hello world");
    }

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
    fn 清理掉模型爱加的外壳() {
        assert_eq!(clean_output("译文：你好"), "你好");
        assert_eq!(clean_output("Translation: hello"), "hello");
        assert_eq!(clean_output("\"你好\""), "你好");
        assert_eq!(clean_output("“你好”"), "你好");
        assert_eq!(clean_output("「你好」"), "你好");
        // 句中引号一律不动（译文本就带引号时不能误伤）
        assert_eq!(clean_output("他说“你好”然后走了"), "他说“你好”然后走了");
        assert_eq!(clean_output("\"他说\"你好\"\""), "\"他说\"你好\"\"");
        // 单独一个引号不算「整体包裹」
        assert_eq!(clean_output("\""), "\"");
    }

    #[test]
    fn 状态码映射出可分类的错误消息() {
        assert!(error_message(401, "Authentication Fails").contains("认证失败"));
        assert!(error_message(402, "Insufficient Balance").contains("余额不足"));
        assert!(error_message(429, "Rate Limit Reached").contains("限流"));
        assert!(error_message(503, "server overloaded").contains("HTTP 503"));
    }
}
