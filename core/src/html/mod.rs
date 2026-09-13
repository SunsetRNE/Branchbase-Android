//! HTML（网页）解析与跳转导航模块
//!
//! 组成：
//! - `matcher` : 链接匹配器（网页/项目/链接三种匹配的统一入口 `resolve_link`）
//!
//! 设计见 `docs/specs/html-parser-design.md`（本模块的契约：§3 规则表、§6 已知边界）。

pub mod matcher;

pub use matcher::{resolve_link, Destination, ResolveContext};
