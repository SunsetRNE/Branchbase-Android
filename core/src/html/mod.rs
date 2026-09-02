//! HTML（网页）解析与跳转导航模块
//!
//! 组成：
//! - `matcher` : 链接匹配器（网页/项目/链接三种匹配的统一入口 `resolve_link`）
//! - `parser`  : 自写 HTML 解析器（README 渲染 HTML → 块级树 + 行内链接解析）
//!
//! 设计见 `docs/html-parser-design.md`。

pub mod matcher;
pub mod parser;

pub use matcher::{resolve_image_src, resolve_link, Destination, ResolveContext};
pub use parser::{parse_html, Block, Inline};

/// 便捷入口：解析 HTML 并返回 `{"blocks":[...]}` JSON 字符串。
pub fn parse_html_json(html: &str, ctx: &ResolveContext) -> Result<String, serde_json::Error> {
    let blocks = parse_html(html, ctx);
    serde_json::to_string(&serde_json::json!({ "blocks": blocks }))
}
