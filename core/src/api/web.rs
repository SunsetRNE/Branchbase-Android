//! GitHub 网页（github.com）通道 —— 用浏览器会话 Cookie 调网页内部端点。
//!
//! ## 为什么需要这条通道
//!
//! 仓库级的「自定义通知」（Watch 面板里的 Custom）**没有公开 API**：
//! - REST 的 `PUT /repos/{o}/{r}/subscription` 只有 `subscribed` / `ignored` 两个布尔；
//! - GraphQL 的 `SubscriptionState` 只有 `SUBSCRIBED` / `IGNORED` / `UNSUBSCRIBED`。
//!
//! 网页版走的是 `POST /{owner}/{repo}/notifications/subscribe`（Rails 内部端点）。
//! OAuth token 到不了那里：实测 github.com 的 HTML 与内部端点**只认浏览器会话 cookie**，
//! 带 `Authorization: token/bearer` 或 Basic 一律 302 到 `/login`。因此 App 侧先用内嵌
//! WebView 让用户登录一次，把 Cookie 交给这里。
//!
//! ## 防伪要求（照搬网页版 `verifiedFetch`，见 notifications-subscriptions-menu 的运行时）
//!
//! 网页版 POST 不带 `authenticity_token` 表单字段，靠这几个头：
//! - `X-Requested-With: XMLHttpRequest`
//! - `X-Fetch-Nonce: <meta name="fetch-nonce">` —— **每次页面加载不同、与会话绑定**，
//!   所以每次写入前都必须重新取一次页面；
//! - `X-GitHub-Client-Version: <meta name="release">`
//! - `GitHub-Verified-Fetch: true`
//!
//! ## 顺带解决「判定准确性」
//!
//! 同一个仓库页的 `react-app.embeddedData` 里 `payload.sidebarAbout` 一次给出网页版的真实关系态：
//! `star.viewerHasStarred` / `fork.canFork` / `fork.forkabilityError` /
//! `watch.subscriptionType` / `watch.subscribableThreadTypes`。
//! 这比 REST 更接近网页语义（例如 `forkabilityError` 能直接区分「自己的仓库」与
//! 「组织禁用了复刻」），而且**一个请求就够**。

use crate::api::client::shared_http;
use crate::error::{CoreError, Result};

/// 网页 UA。
///
/// 用桌面 Chrome：GitHub 的 React 页面在桌面 UA 下才会输出
/// `react-app.embeddedData`（判定数据的载体）；移动 UA 会拿到精简页面。
const WEB_UA: &str =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

/// 仓库页 embeddedData 的定位标记（`<script type="application/json" data-target="…">`）。
const EMBEDDED_MARK: &str = "data-target=\"react-app.embeddedData\">";

/// 一个已登录的 GitHub 网页会话（cookie 由 App 的 WebView 导出）。
#[derive(Debug, Clone)]
pub struct WebSession {
    host: String,
    cookie: String,
}

impl WebSession {
    pub fn new(host: impl Into<String>, cookie: impl Into<String>) -> Self {
        Self { host: host.into(), cookie: cookie.into() }
    }

    /// 网页站点的根（网页端点在 github.com，不在 api.github.com）。
    fn base(&self) -> String {
        if self.host.is_empty() || self.host == "github.com" {
            "https://github.com".to_string()
        } else {
            format!("https://{}", self.host)
        }
    }

    /// 带会话的网页 GET，返回 HTML 文本。
    ///
    /// cookie 为空 = **匿名访问**：判定数据里 `canStar`/`canWatch` 都是 false，
    /// 但 Custom 的可选事件清单（`subscribableThreadTypes`）依然可读 ——
    /// 它是仓库能力而非用户状态，公开页就有。
    pub async fn get_html(&self, path: &str) -> Result<String> {
        let url = format!("{}{}", self.base(), path);
        let mut req = shared_http()
            .get(&url)
            .header("User-Agent", WEB_UA)
            .header("Accept", "text/html,application/xhtml+xml");
        if !self.cookie.is_empty() {
            req = req.header("Cookie", &self.cookie);
        }
        let resp = req.send().await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}")));
        }
        Ok(text)
    }

    /// 仓库页的判定数据（`react-app.embeddedData` 的 JSON 文本，已从 230KB 页面里剥出来）。
    pub async fn repo_embedded(&self, owner: &str, repo: &str) -> Result<String> {
        let html = self.get_html(&format!("/{owner}/{repo}")).await?;
        extract_embedded_data(&html)
            .map(|s| s.to_string())
            .ok_or_else(|| CoreError::Other("网页结构变化：找不到 react-app.embeddedData".into()))
    }

    /// 调一个网页内部 POST 端点（网页版 FormData 的等价形态）。
    ///
    /// 自己先取一次 [page_path] 拿当次的 `fetch-nonce` / `release`，再发 POST ——
    /// nonce 与会话绑定且每次页面加载都变，复用旧值会被判为伪造请求（422）。
    pub async fn post_form(
        &self,
        page_path: &str,
        post_path: &str,
        fields: &[(String, String)],
    ) -> Result<String> {
        let html = self.get_html(page_path).await?;
        // 会话过期的最早信号：已登录页面才会带非空的 user-login
        if extract_meta(&html, "user-login").unwrap_or_default().is_empty() {
            return Err(CoreError::Other("网页会话已失效，请重新登录 GitHub".into()));
        }
        let nonce = extract_meta(&html, "fetch-nonce").unwrap_or_default();
        let version = extract_meta(&html, "release").unwrap_or_default();

        let mut serializer = url::form_urlencoded::Serializer::new(String::new());
        for (k, v) in fields {
            serializer.append_pair(k, v);
        }
        let body = serializer.finish();

        let base = self.base();
        let url = format!("{base}{post_path}");
        let resp = shared_http()
            .post(&url)
            .header("Cookie", &self.cookie)
            .header("User-Agent", WEB_UA)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-Fetch-Nonce", nonce)
            .header("X-GitHub-Client-Version", version)
            .header("GitHub-Verified-Fetch", "true")
            .header("Referer", format!("{base}{page_path}"))
            .header("Origin", base)
            .body(body)
            .send()
            .await?;

        let status = resp.status();
        let text = resp.text().await?;
        // 未跟随重定向：302/303 基本就是会话掉了（网页端会踢回登录页）
        if status.is_redirection() {
            return Err(CoreError::Other("网页会话已失效，请重新登录 GitHub".into()));
        }
        if !status.is_success() {
            let brief: String = text.chars().take(200).collect();
            return Err(CoreError::Other(format!("HTTP {status}: {brief}")));
        }
        Ok(text)
    }
}

/// 取出 `react-app.embeddedData` 那段 JSON（找不到返回 None）。
///
/// 不用正则：页面 230KB，而标记唯一、`</script>` 紧随其后，字符串查找足够且没有额外依赖。
pub fn extract_embedded_data(html: &str) -> Option<&str> {
    let start = html.find(EMBEDDED_MARK)? + EMBEDDED_MARK.len();
    let rest = &html[start..];
    let end = rest.find("</script>")?;
    Some(rest[..end].trim())
}

/// 取 `<meta name="X" content="Y">` 的 Y。
///
/// GitHub 输出的属性顺序固定为 `name` 在前、`content` 紧随（`<meta name="release" content="…"
/// data-turbo-track="reload">` 这种尾巴不影响）；仍做循环查找，避免页面里出现同名子串时取错。
pub fn extract_meta(html: &str, name: &str) -> Option<String> {
    let needle = format!("name=\"{name}\"");
    let mut from = 0usize;
    while let Some(rel) = html[from..].find(&needle) {
        let idx = from + rel;
        let end = (idx + 400).min(html.len());
        let window = &html[idx..end];
        if let Some(c) = window.find("content=\"") {
            let s = c + "content=\"".len();
            if let Some(e) = window[s..].find('"') {
                return Some(window[s..s + e].to_string());
            }
        }
        from = idx + needle.len();
    }
    None
}

#[cfg(test)]
mod tests {
    use super::{extract_embedded_data, extract_meta};

    #[test]
    fn meta_content_is_read() {
        let html = r#"<meta name="fetch-nonce" content="v2:abc-123"><meta name="user-login" content="">"#;
        assert_eq!(extract_meta(html, "fetch-nonce").as_deref(), Some("v2:abc-123"));
        assert_eq!(extract_meta(html, "user-login").as_deref(), Some(""));
        assert_eq!(extract_meta(html, "release"), None);
    }

    /// `release` 后面还挂着别的属性 —— 不能把 ` data-turbo-track="reload"` 一起吃进去。
    #[test]
    fn meta_stops_at_closing_quote() {
        let html = r#"<meta name="release" content="54447dd" data-turbo-track="reload">"#;
        assert_eq!(extract_meta(html, "release").as_deref(), Some("54447dd"));
    }

    #[test]
    fn embedded_data_is_extracted() {
        let html = r#"<div><script type="application/json" data-target="react-app.embeddedData">{"payload":{"a":1}}</script></div>"#;
        assert_eq!(extract_embedded_data(html), Some(r#"{"payload":{"a":1}}"#));
        assert_eq!(extract_embedded_data("<html></html>"), None);
    }
}
