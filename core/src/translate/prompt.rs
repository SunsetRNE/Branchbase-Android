//! 各家协议**共用**的提示词与输出清理（与协议无关）。
//!
//! ## 为什么单独拆出来
//!
//! 五种写法（Chat Completions / Responses / Messages / Gemini）的**载荷结构**不同，
//! 但「翻译成什么、哪些东西不能动、输出外壳怎么剥」是**同一件事**。1.4.53 之前这些
//! 函数挂在 `deepseek` 模块里，其它协议只能跨模块引用一个叫 DeepSeek 的东西 —— 那正是
//! 「协议被锁死在某一家」的具体形态。现在它们与协议无关，谁都可以用。
//!
//! ## 提示词里唯一真正承担职责的一条
//!
//! Kotlin 侧在翻译前会把 URL / `@提及` / `#编号` / 提交 SHA 换成 `⟦n⟧`（见 `PlaceholderGuard`）。
//! 模型**必须原样保留**这些标记，否则还原会失败、整段退化成「不加保护再翻一次」。
//! 所以「占位符原样保留」写成了 system 里的硬性要求，`prompt` 的单测钉住它不许被删。

/// 翻译用的系统提示词（目标语言 + 源语言 + 占位符与格式约束）。
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

/// `chat/completions` 形态的消息数组（system 一条 + user 一条）。
pub fn build_messages(text: &str, from: &str, to: &str) -> Vec<serde_json::Value> {
    vec![
        serde_json::json!({"role": "system", "content": system_prompt(from, to)}),
        serde_json::json!({"role": "user", "content": text}),
    ]
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
}
