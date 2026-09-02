//! 自写轻量 HTML 解析器（面向 GitHub 已渲染 README 的 HTML）
//!
//! 三步流水线：词法切分 → 块级构建 → 行内链接解析。
//! 不依赖第三方 HTML 库，保证 `.so` 体积与构建稳定（对齐「自写解析器」诉求）。
//!
//! 已知取舍见 `docs/html-parser-design.md` §4.5。

use serde::Serialize;

use crate::html::matcher::{resolve_image_src, resolve_link, Destination, ResolveContext};

/// 行内内容（链接 / 加粗 / 斜体 / 行内代码 / 纯文本 / 删除线 / 图片）
///
/// 采用「行内树」模型：
/// - 叶子节点 `text` / `code` / `image` 的文本（或图片地址）放在 `value` / `src`；
/// - 容器节点 `link` / `bold` / `italic` / `strike` 的子内容放在 `children`。
/// 这样 `粗体包裹链接` 之类的嵌套能精确保留顺序与链接目标。
#[derive(Debug, Clone, Serialize)]
pub struct Inline {
    /// text / link / code / bold / italic / strike / image
    #[serde(rename = "t")]
    pub kind: &'static str,
    /// 叶子节点（text/code/image 的 alt）的文本；容器节点为空串
    #[serde(rename = "v")]
    pub value: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub href: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dest: Option<Destination>,
    /// 图片节点（kind=image）的地址；其余节点为 None
    #[serde(skip_serializing_if = "Option::is_none")]
    pub src: Option<String>,
    /// 容器节点的嵌套子节点（叶子为空）
    #[serde(default, skip_serializing_if = "Vec::is_empty", rename = "c")]
    pub children: Vec<Inline>,
}

/// 块级内容
#[derive(Debug, Clone, Default, Serialize)]
pub struct Block {
    /// heading / paragraph / list_item / code / blockquote / image / table / hr / details
    #[serde(rename = "type")]
    pub kind: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub level: Option<u8>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub depth: Option<u8>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ordered: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub index: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub lang: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub src: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub alt: Option<String>,
    /// 图片被链接包裹时的跳转目标（徽章 `<a><img></a>` 可点击）
    #[serde(skip_serializing_if = "Option::is_none")]
    pub href: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dest: Option<Destination>,
    /// 任务列表项 `- [x]` 的勾选状态（None = 非任务项）
    #[serde(skip_serializing_if = "Option::is_none")]
    pub checked: Option<bool>,
    /// 折叠块 `<details>` 的标题行内内容（`<summary>`）
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub summary: Vec<Inline>,
    /// 折叠块 `<details>` 展开后的子块
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub children: Vec<Block>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub inline: Vec<Inline>,
    /// 表格行；每格是行内树（支持单元格内链接 / 加粗 / 行内代码等）
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub rows: Vec<Vec<Vec<Inline>>>,
}

/// 解析 HTML，返回块列表（每个链接已解析为 dest）。
pub fn parse_html(html: &str, ctx: &ResolveContext) -> Vec<Block> {
    let events = tokenize(html);
    build_blocks(&events, ctx)
}

// ── ① 词法分析 ────────────────────────────────────────────

#[derive(Debug)]
enum Event {
    Open(String, Vec<(String, String)>),
    Close(String),
    SelfClose(String, Vec<(String, String)>),
    Text(String),
}

fn tokenize(html: &str) -> Vec<Event> {
    let mut events = Vec::new();
    let bytes = html.as_bytes();
    let mut i = 0usize;
    let n = bytes.len();

    let mut text_buf = String::new();

    // 从 i 开始的文本推进到下一个 '<'
    macro_rules! flush_text {
        () => {
            if !text_buf.is_empty() {
                events.push(Event::Text(decode_entities(&text_buf)));
                text_buf.clear();
            }
        };
    }

    while i < n {
        if bytes[i] == b'<' {
            // 注释 / 声明
            if i + 1 < n && (bytes[i + 1] == b'!' || bytes[i + 1] == b'?') {
                flush_text!();
                // 跳过直到 '>'
                while i < n && bytes[i] != b'>' {
                    i += 1;
                }
                i += 1; // 跳过 '>'
                continue;
            }
            // 结束标签 </name>
            if i + 1 < n && bytes[i + 1] == b'/' {
                let j = i + 2;
                let mut k = j;
                while k < n && !(bytes[k] == b'>' || bytes[k].is_ascii_whitespace()) {
                    k += 1;
                }
                let name = html[j..k].to_ascii_lowercase();
                flush_text!();
                events.push(Event::Close(name));
                while i < n && bytes[i] != b'>' {
                    i += 1;
                }
                i += 1;
                continue;
            }
            // 开始标签 <name attrs...>
            let j = i + 1;
            let mut k = j;
            while k < n && !(bytes[k] == b'>' || bytes[k] == b'/' || bytes[k].is_ascii_whitespace()) {
                k += 1;
            }
            if k == j {
                // 孤立 '<'
                text_buf.push('<');
                i += 1;
                continue;
            }
            let name = html[j..k].to_ascii_lowercase();
            // 解析属性
            let mut attrs = Vec::new();
            let mut pos = k;
            let mut self_close = false;
            loop {
                while pos < n && bytes[pos].is_ascii_whitespace() {
                    pos += 1;
                }
                if pos >= n {
                    break;
                }
                if bytes[pos] == b'>' {
                    pos += 1;
                    break;
                }
                if bytes[pos] == b'/' && pos + 1 < n && bytes[pos + 1] == b'>' {
                    self_close = true;
                    pos += 2;
                    break;
                }
                // 读属性名
                let ak = pos;
                while pos < n && !(bytes[pos] == b'=' || bytes[pos] == b'>' || bytes[pos] == b'/' || bytes[pos].is_ascii_whitespace()) {
                    pos += 1;
                }
                let attr_name = html[ak..pos].to_ascii_lowercase();
                while pos < n && bytes[pos].is_ascii_whitespace() {
                    pos += 1;
                }
                let mut attr_val = String::new();
                if pos < n && bytes[pos] == b'=' {
                    pos += 1;
                    while pos < n && bytes[pos].is_ascii_whitespace() {
                        pos += 1;
                    }
                    if pos < n && (bytes[pos] == b'"' || bytes[pos] == b'\'') {
                        let quote = bytes[pos];
                        pos += 1;
                        let vs = pos;
                        while pos < n && bytes[pos] != quote {
                            pos += 1;
                        }
                        attr_val = html[vs..pos].to_string();
                        pos += 1;
                    } else {
                        let vs = pos;
                        while pos < n && !(bytes[pos] == b'>' || bytes[pos] == b'/' || bytes[pos].is_ascii_whitespace()) {
                            pos += 1;
                        }
                        attr_val = html[vs..pos].to_string();
                    }
                }
                attrs.push((attr_name, attr_val));
            }
            flush_text!();
            if self_close {
                events.push(Event::SelfClose(name, attrs));
            } else {
                events.push(Event::Open(name, attrs));
            }
            i = pos;
        } else {
            // 普通文本（按 UTF-8 边界推进）
            let start = i;
            while i < n && bytes[i] != b'<' {
                i += 1;
            }
            text_buf.push_str(&html[start..i]);
        }
    }
    flush_text!();
    events
}

// ── 实体解码 ──────────────────────────────────────────────

fn decode_entities(s: &str) -> String {
    if !s.contains('&') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars().peekable();
    while let Some(c) = chars.next() {
        if c != '&' {
            out.push(c);
            continue;
        }
        // 尝试读取实体名
        let mut name = String::new();
        let mut consumed = 0usize;
        let mut found = false;
        for ch in chars.clone() {
            consumed += 1;
            if ch == ';' {
                found = true;
                break;
            }
            if ch == '&' || ch == '<' || ch.is_whitespace() || name.len() > 32 {
                break;
            }
            name.push(ch);
        }
        if found {
            for _ in 0..consumed {
                chars.next();
            }
            match decode_entity(&name) {
                Some(ch) => out.push(ch),
                None => {
                    out.push('&');
                    out.push_str(&name);
                    out.push(';');
                }
            }
        } else {
            out.push('&');
        }
    }
    out
}

fn decode_entity(name: &str) -> Option<char> {
    if let Some(rest) = name.strip_prefix('#') {
        let (val, radix) = if let Some(h) = rest.strip_prefix('x').or_else(|| rest.strip_prefix('X')) {
            (h, 16)
        } else {
            (rest, 10)
        };
        let code = u32::from_str_radix(val, radix).ok()?;
        return char::from_u32(code);
    }
    Some(match name {
        "amp" => '&',
        "lt" => '<',
        "gt" => '>',
        "quot" => '"',
        "apos" => '\'',
        "nbsp" => '\u{00A0}',
        "copy" => '©',
        "reg" => '®',
        "hellip" => '…',
        "mdash" => '—',
        "ndash" => '–',
        "laquo" => '«',
        "raquo" => '»',
        "middot" => '·',
        "bull" => '•',
        "sect" => '§',
        "deg" => '°',
        "plusmn" => '±',
        "times" => '×',
        "divide" => '÷',
        _ => return None,
    })
}

// ── ②③ 块级构建 + 行内链接解析 ───────────────────────────

fn attr<'a>(attrs: &'a [(String, String)], key: &str) -> Option<&'a str> {
    attrs.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
}

// 行内树构建中的容器节点
struct OpenInline {
    kind: &'static str,
    href: Option<String>,
    children: Vec<Inline>,
}

/// 行内构建上下文：`out` 为顶层行内列表，`stack` 为嵌套容器栈。
#[derive(Default)]
struct InlineCtx {
    out: Vec<Inline>,
    stack: Vec<OpenInline>,
}

impl InlineCtx {
    fn push_text(&mut self, s: &str) {
        if s.is_empty() {
            return;
        }
        let leaf = Inline {
            kind: "text",
            value: s.to_string(),
            href: None,
            dest: None,
            src: None,
            children: Vec::new(),
        };
        if let Some(top) = self.stack.last_mut() {
            top.children.push(leaf);
        } else {
            self.out.push(leaf);
        }
    }

    fn open(&mut self, kind: &'static str, href: Option<String>) {
        self.stack.push(OpenInline { kind, href, children: Vec::new() });
    }

    fn close(&mut self, ctx: &ResolveContext) {
        let Some(node) = self.stack.pop() else { return };
        let built = build_inline(node, ctx);
        if let Some(top) = self.stack.last_mut() {
            top.children.push(built);
        } else {
            self.out.push(built);
        }
    }

    /// 追加一个行内图片节点。若图片被 `<a>` 包裹（徽章），则捕获其链接作为可点击目标
    /// （图片作为链接的子节点，保留链接内的其它文字内容）。
    fn push_image(&mut self, src: String, alt: String, ctx: &ResolveContext) {
        let href = self.stack.iter().rev().find(|n| n.kind == "link").and_then(|n| n.href.clone());
        let dest = href.as_deref().map(|h| resolve_link(h, ctx));
        let img = Inline {
            kind: "image",
            value: alt,
            href,
            dest,
            src: Some(src),
            children: Vec::new(),
        };
        if let Some(top) = self.stack.last_mut() {
            top.children.push(img);
        } else {
            self.out.push(img);
        }
    }

    /// 是否正处于链接容器内。
    fn in_link(&self) -> bool {
        self.stack.iter().any(|n| n.kind == "link")
    }

    fn take_out(&mut self) -> Vec<Inline> {
        std::mem::take(&mut self.out)
    }
}

/// 把闭合的容器节点构建为最终的 `Inline`。
fn build_inline(node: OpenInline, ctx: &ResolveContext) -> Inline {
    match node.kind {
        // 行内代码：子文本合并为 value（叶子节点）
        "code" => {
            let value: String = node.children.iter().map(|c| c.value.as_str()).collect();
            Inline { kind: "code", value, href: None, dest: None, src: None, children: Vec::new() }
        }
        // 链接：容器节点，携带 href/dest 与子内容
        "link" => {
            let dest = node.href.as_deref().map(|h| resolve_link(h, ctx));
            Inline { kind: "link", value: String::new(), href: node.href, dest, src: None, children: node.children }
        }
        // bold / italic / strike：容器节点
        _ => Inline { kind: node.kind, value: String::new(), href: None, dest: None, src: None, children: node.children },
    }
}

struct ListCtx {
    ordered: bool,
    index: u64,
    depth: u8,
}

/// 折叠块 `<details>` 的累积上下文
struct DetailsCtx {
    summary: Vec<Inline>,
    children: Vec<Block>,
}

fn build_blocks(events: &[Event], ctx: &ResolveContext) -> Vec<Block> {
    let mut blocks: Vec<Block> = Vec::new();

    // 当前块的行内上下文
    let mut inline = InlineCtx::default();

    // 当前块的元信息
    let mut cur_kind: Option<&'static str> = None;
    let mut cur_level: Option<u8> = None;
    let mut cur_checked: Option<bool> = None;

    // 列表上下文
    let mut list_stack: Vec<ListCtx> = Vec::new();

    // 代码块
    let mut in_pre = false;
    let mut pre_lang: Option<String> = None;
    let mut pre_text = String::new();

    // 表格：每格是行内树（支持单元格内链接等）
    let mut in_table = false;
    let mut table_rows: Vec<Vec<Vec<Inline>>> = Vec::new();
    let mut cur_row: Vec<Vec<Inline>> = Vec::new();
    let mut cell: Option<InlineCtx> = None;

    // 折叠块 <details>
    let mut details_stack: Vec<DetailsCtx> = Vec::new();
    let mut summary: Option<InlineCtx> = None;

    // 把块推入当前目标（顶层 blocks，或 details 的 children）
    fn push_block(blocks: &mut Vec<Block>, details_stack: &mut [DetailsCtx], b: Block) {
        if let Some(d) = details_stack.last_mut() {
            d.children.push(b);
        } else {
            blocks.push(b);
        }
    }

    // 取当前行内上下文（优先级：单元格 > summary > 块）
    fn cur_inline<'a>(
        inline: &'a mut InlineCtx,
        cell: &'a mut Option<InlineCtx>,
        summary: &'a mut Option<InlineCtx>,
    ) -> &'a mut InlineCtx {
        if let Some(c) = cell.as_mut() {
            c
        } else if let Some(s) = summary.as_mut() {
            s
        } else {
            inline
        }
    }

    // 收尾当前块（flush 行内内容）
    macro_rules! flush_inline {
        () => {
            if let Some(kind) = cur_kind.take() {
                let level = cur_level.take();
                let checked = cur_checked.take();
                let out = inline.take_out();
                if !out.is_empty() {
                    let mut b = Block { kind, level, inline: out, ..Block::default() };
                    if kind == "list_item" {
                        if let Some(lc) = list_stack.last() {
                            b.depth = Some(lc.depth);
                            b.ordered = Some(lc.ordered);
                            b.index = Some(lc.index);
                        }
                        b.checked = checked;
                    }
                    push_block(&mut blocks, &mut details_stack, b);
                }
            }
        };
    }

    // 处理图片：单元格 / summary / 链接内为行内图片；顶层独立为块级图片。
    macro_rules! handle_img {
        ($attrs:expr) => {
            let src = attr($attrs, "src").unwrap_or("").to_string();
            let alt = attr($attrs, "alt").unwrap_or("").to_string();
            let resolved = resolve_image_src(&src, ctx);
            if let Some(c) = cell.as_mut() {
                c.push_image(resolved, alt, ctx);
            } else if let Some(s) = summary.as_mut() {
                s.push_image(resolved, alt, ctx);
            } else if inline.in_link() {
                inline.push_image(resolved, alt, ctx);
            } else {
                flush_inline!();
                push_image_block(&mut blocks, &mut details_stack, resolved, alt);
            }
        };
    }

    for ev in events {
        match ev {
            Event::Text(s) => {
                if in_pre {
                    pre_text.push_str(s);
                } else {
                    cur_inline(&mut inline, &mut cell, &mut summary).push_text(s);
                }
            }
            Event::Open(name, attrs) => match name.as_str() {
                "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
                    flush_inline!();
                    cur_kind = Some("heading");
                    cur_level = Some(match name.as_str() {
                        "h1" => 1,
                        "h2" => 2,
                        "h3" => 3,
                        "h4" => 4,
                        "h5" => 5,
                        _ => 6,
                    });
                }
                "p" => {
                    flush_inline!();
                    cur_kind = Some("paragraph");
                }
                "blockquote" => {
                    flush_inline!();
                    cur_kind = Some("blockquote");
                }
                "li" => {
                    flush_inline!();
                    if let Some(lc) = list_stack.last_mut() {
                        lc.index += 1;
                    }
                    cur_kind = Some("list_item");
                }
                "ul" | "ol" => {
                    flush_inline!();
                    let ordered = name == "ol";
                    let depth = (list_stack.len() as u8) + 1;
                    list_stack.push(ListCtx { ordered, index: 0, depth });
                }
                "pre" => {
                    flush_inline!();
                    in_pre = true;
                    pre_lang = None;
                    pre_text.clear();
                }
                "code" => {
                    if in_pre {
                        if let Some(cls) = attr(attrs, "class") {
                            pre_lang = cls
                                .split_whitespace()
                                .find(|c| c.starts_with("language-"))
                                .map(|c| c.trim_start_matches("language-").to_string());
                        }
                    } else {
                        cur_inline(&mut inline, &mut cell, &mut summary).open("code", None);
                    }
                }
                "a" => {
                    let href = attr(attrs, "href").map(|s| s.to_string());
                    cur_inline(&mut inline, &mut cell, &mut summary).open("link", href);
                }
                "strong" | "b" => cur_inline(&mut inline, &mut cell, &mut summary).open("bold", None),
                "em" | "i" => cur_inline(&mut inline, &mut cell, &mut summary).open("italic", None),
                "del" | "s" => cur_inline(&mut inline, &mut cell, &mut summary).open("strike", None),
                "br" => cur_inline(&mut inline, &mut cell, &mut summary).push_text("\n"),
                "details" => {
                    flush_inline!();
                    details_stack.push(DetailsCtx { summary: Vec::new(), children: Vec::new() });
                }
                "summary" => {
                    summary = Some(InlineCtx::default());
                }
                "table" => {
                    flush_inline!();
                    in_table = true;
                    table_rows.clear();
                    cur_row.clear();
                    cell = None;
                }
                "tr" => {
                    if in_table {
                        cur_row.clear();
                        cell = None;
                    }
                }
                "td" | "th" => {
                    if in_table {
                        cell = Some(InlineCtx::default());
                    }
                }
                "img" => {
                    handle_img!(attrs);
                }
                "hr" => {
                    flush_inline!();
                    push_block(&mut blocks, &mut details_stack, Block { kind: "hr", ..Block::default() });
                }
                "input" => {
                    // 任务列表复选框 `<input type="checkbox" checked disabled>`（HTML void 元素，无闭合标签）
                    let is_checkbox = attr(attrs, "type")
                        .map(|t| t.eq_ignore_ascii_case("checkbox"))
                        .unwrap_or(false);
                    if is_checkbox {
                        let checked = attrs.iter().any(|(k, _)| k == "checked");
                        cur_checked = Some(checked);
                    }
                }
                _ => {}
            },
            Event::SelfClose(name, attrs) => {
                if name == "img" {
                    handle_img!(attrs);
                } else if name == "br" {
                    cur_inline(&mut inline, &mut cell, &mut summary).push_text("\n");
                } else if name == "input" {
                    // 任务列表复选框 `<input type="checkbox" checked disabled>`
                    let is_checkbox = attr(attrs, "type")
                        .map(|t| t.eq_ignore_ascii_case("checkbox"))
                        .unwrap_or(false);
                    if is_checkbox {
                        let checked = attrs.iter().any(|(k, _)| k == "checked");
                        cur_checked = Some(checked);
                    }
                }
            }
            Event::Close(name) => match name.as_str() {
                "h1" | "h2" | "h3" | "h4" | "h5" | "h6" | "p" | "blockquote" | "li" => {
                    flush_inline!();
                }
                "ul" | "ol" => {
                    flush_inline!();
                    list_stack.pop();
                }
                "pre" => {
                    if in_pre {
                        let text = pre_text.trim_matches('\n').to_string();
                        push_block(&mut blocks, &mut details_stack, Block {
                            kind: "code",
                            lang: pre_lang.take(),
                            text: Some(text),
                            ..Block::default()
                        });
                        pre_text.clear();
                        in_pre = false;
                    }
                }
                "a" | "code" | "strong" | "b" | "em" | "i" | "del" | "s" => {
                    cur_inline(&mut inline, &mut cell, &mut summary).close(ctx);
                }
                "summary" => {
                    if let Some(d) = details_stack.last_mut() {
                        if let Some(sm) = summary.take() {
                            d.summary = sm.out;
                        }
                    }
                }
                "details" => {
                    flush_inline!();
                    if let Some(d) = details_stack.pop() {
                        let b = Block {
                            kind: "details",
                            summary: d.summary,
                            children: d.children,
                            ..Block::default()
                        };
                        push_block(&mut blocks, &mut details_stack, b);
                    }
                }
                "td" | "th" => {
                    if in_table {
                        let cell_inline = cell.take().map(|c| c.out).unwrap_or_default();
                        cur_row.push(cell_inline);
                    }
                }
                "tr" => {
                    if in_table {
                        table_rows.push(std::mem::take(&mut cur_row));
                    }
                }
                "table" => {
                    if in_table {
                        if !table_rows.is_empty() {
                            push_block(&mut blocks, &mut details_stack, Block {
                                kind: "table",
                                rows: std::mem::take(&mut table_rows),
                                ..Block::default()
                            });
                        }
                        in_table = false;
                        cell = None;
                    }
                }
                _ => {}
            },
        }
    }
    flush_inline!();

    blocks
}

/// 追加一个块级图片（顶层独立图片，无链接包裹）。
fn push_image_block(blocks: &mut Vec<Block>, details_stack: &mut [DetailsCtx], src: String, alt: String) {
    let b = Block { kind: "image", src: Some(src), alt: Some(alt), ..Block::default() };
    if let Some(d) = details_stack.last_mut() {
        d.children.push(b);
    } else {
        blocks.push(b);
    }
}

// ── 单元测试 ──────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx() -> ResolveContext {
        ResolveContext {
            host: "github.com".into(),
            owner: "SunsetRNE".into(),
            repo: "branchbase".into(),
            branch: "main".into(),
            base_dir: "".into(),
            current_user: "SunsetRNE".into(),
        }
    }

    #[test]
    fn test_heading_paragraph() {
        let html = "<h1>Title</h1><p>Hello <strong>world</strong></p>";
        let blocks = parse_html(html, &ctx());
        assert_eq!(blocks.len(), 2);
        assert_eq!(blocks[0].kind, "heading");
        assert_eq!(blocks[0].level, Some(1));
        assert_eq!(blocks[1].kind, "paragraph");
    }

    #[test]
    fn test_link_resolved() {
        let html = "<p>See <a href=\"https://github.com/other/repo\">repo</a></p>";
        let blocks = parse_html(html, &ctx());
        let inline = &blocks[0].inline;
        assert_eq!(inline.len(), 2);
        assert_eq!(inline[1].kind, "link");
        let dest = inline[1].dest.as_ref().unwrap();
        assert_eq!(dest.dest_type, "repo");
        assert!(!dest.is_own);
    }

    #[test]
    fn test_relative_link() {
        let html = "<p><a href=\"docs/README.md\">readme</a></p>";
        let blocks = parse_html(html, &ctx());
        let dest = blocks[0].inline[0].dest.as_ref().unwrap();
        assert_eq!(dest.dest_type, "blob");
        assert_eq!(dest.path.as_deref(), Some("docs/README.md"));
    }

    #[test]
    fn test_code_block_lang() {
        let html = "<pre><code class=\"language-rust\">fn main() {}</code></pre>";
        let blocks = parse_html(html, &ctx());
        assert_eq!(blocks[0].kind, "code");
        assert_eq!(blocks[0].lang.as_deref(), Some("rust"));
        assert_eq!(blocks[0].text.as_deref(), Some("fn main() {}"));
    }

    #[test]
    fn test_entity() {
        let html = "<p>a &amp; b &lt;c&gt;</p>";
        let blocks = parse_html(html, &ctx());
        let v = blocks[0].inline.iter().map(|i| i.value.as_str()).collect::<String>();
        assert_eq!(v, "a & b <c>");
    }

    #[test]
    fn test_list() {
        let html = "<ul><li>one</li><li>two</li></ul>";
        let blocks = parse_html(html, &ctx());
        assert_eq!(blocks.len(), 2);
        assert_eq!(blocks[0].kind, "list_item");
        assert_eq!(blocks[0].ordered, Some(false));
        assert_eq!(blocks[0].index, Some(1));
        assert_eq!(blocks[1].index, Some(2));
    }

    #[test]
    fn test_badge_image_in_link() {
        let html = "<p><a href=\"https://github.com/SunsetRNE/branchbase\"><img src=\"https://img.shields.io/x\" alt=\"badge\"></a></p>";
        let blocks = parse_html(html, &ctx());
        // 徽章是段落内「链接包裹图片」，链接可点击、图片地址保留
        assert_eq!(blocks.len(), 1);
        assert_eq!(blocks[0].kind, "paragraph");
        let link = &blocks[0].inline[0];
        assert_eq!(link.kind, "link");
        assert_eq!(link.dest.as_ref().unwrap().dest_type, "repo");
        let img = &link.children[0];
        assert_eq!(img.kind, "image");
        assert_eq!(img.src.as_deref(), Some("https://img.shields.io/x"));
        assert_eq!(img.value, "badge");
    }

    #[test]
    fn test_link_image_with_text() {
        // 链接内既有图片又有文字：文字不应丢失（特别特殊的链接徽章）
        let html = "<p><a href=\"https://github.com/SunsetRNE/branchbase\"><img src=\"https://img.shields.io/x\" alt=\"badge\"> 查看详情</a></p>";
        let blocks = parse_html(html, &ctx());
        assert_eq!(blocks[0].kind, "paragraph");
        let link = &blocks[0].inline[0];
        assert_eq!(link.kind, "link");
        assert_eq!(link.children.len(), 2);
        assert_eq!(link.children[0].kind, "image");
        assert_eq!(link.children[1].kind, "text");
        assert_eq!(link.children[1].value, " 查看详情");
    }

    #[test]
    fn test_table_cell_image() {
        let html = "<table><tr><td><a href=\"https://github.com/other/repo\"><img src=\"https://img.shields.io/x\" alt=\"badge\"></a></td></tr></table>";
        let blocks = parse_html(html, &ctx());
        assert_eq!(blocks[0].kind, "table");
        let cell = &blocks[0].rows[0][0];
        assert_eq!(cell[0].kind, "link");
        let img = &cell[0].children[0];
        assert_eq!(img.kind, "image");
        assert_eq!(img.dest.as_ref().unwrap().dest_type, "repo");
    }

    #[test]
    fn test_summary_image() {
        let html = "<details><summary><img src=\"https://img.shields.io/x\" alt=\"badge\"> 标题</summary><p>内容</p></details>";
        let blocks = parse_html(html, &ctx());
        assert_eq!(blocks[0].kind, "details");
        // summary 内是 image + text
        assert_eq!(blocks[0].summary[0].kind, "image");
        assert_eq!(blocks[0].summary[1].kind, "text");
    }

    #[test]
    fn test_relative_image_src() {
        let html = "<p><img src=\"./docs/images/x.png\" alt=\"x\"></p>";
        let blocks = parse_html(html, &ctx());
        assert_eq!(blocks[0].kind, "image");
        assert_eq!(
            blocks[0].src.as_deref(),
            Some("https://raw.githubusercontent.com/SunsetRNE/branchbase/main/docs/images/x.png")
        );
    }

    #[test]
    fn test_strikethrough() {
        let html = "<p><del>gone</del> <s>also</s></p>";
        let blocks = parse_html(html, &ctx());
        let kinds: Vec<&str> = blocks[0].inline.iter().map(|i| i.kind).collect();
        assert_eq!(kinds, vec!["strike", "text", "strike"]);
        // 删除线是容器节点，文本在其 children 中
        assert_eq!(blocks[0].inline[0].children[0].value, "gone");
    }

    #[test]
    fn test_nested_inline() {
        let html = "<p><strong>see <a href=\"https://github.com/other/repo\">repo</a></strong></p>";
        let blocks = parse_html(html, &ctx());
        let inline = &blocks[0].inline;
        assert_eq!(inline[0].kind, "bold");
        let bold_children = &inline[0].children;
        assert_eq!(bold_children.len(), 2);
        assert_eq!(bold_children[0].kind, "text");
        assert_eq!(bold_children[1].kind, "link");
        assert_eq!(bold_children[1].dest.as_ref().unwrap().dest_type, "repo");
    }

    #[test]
    fn test_table_cell_inline() {
        let html = "<table><tr><th>Name</th><th>Link</th></tr><tr><td>foo</td><td><a href=\"https://github.com/other/repo\">repo</a></td></tr></table>";
        let blocks = parse_html(html, &ctx());
        assert_eq!(blocks[0].kind, "table");
        let rows = &blocks[0].rows;
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0][0][0].value, "Name");
        assert_eq!(rows[1][0][0].value, "foo");
        // 单元格内的链接被正确解析为 link 节点
        let cell = &rows[1][1];
        assert_eq!(cell[0].kind, "link");
        assert_eq!(cell[0].dest.as_ref().unwrap().dest_type, "repo");
    }

    #[test]
    fn test_details() {
        let html = "<details><summary>展开详情</summary><p>内容</p></details>";
        let blocks = parse_html(html, &ctx());
        assert_eq!(blocks.len(), 1);
        assert_eq!(blocks[0].kind, "details");
        assert_eq!(blocks[0].summary[0].value, "展开详情");
        assert_eq!(blocks[0].children.len(), 1);
        assert_eq!(blocks[0].children[0].kind, "paragraph");
    }

    #[test]
    fn test_task_list() {
        let html = "<ul><li><input type=\"checkbox\" checked disabled>done</li><li><input type=\"checkbox\" disabled>todo</li></ul>";
        let blocks = parse_html(html, &ctx());
        assert_eq!(blocks.len(), 2);
        assert_eq!(blocks[0].checked, Some(true));
        assert_eq!(blocks[1].checked, Some(false));
    }
}