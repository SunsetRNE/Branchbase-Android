//! 底层 HTTP 客户端（封装 reqwest，统一注入 token 与请求头）

use crate::error::{CoreError, Result};
use std::sync::OnceLock;
use std::time::Duration;

/// 进程内共享的 HTTP 客户端。
///
/// **为什么必须共享**：每个 JNI 调用各自 `reqwest::Client::builder().build()` 会拿到
/// 全新的连接池与 TLS 会话 —— 实测一次冷连接比复用连接多约 **390ms**
/// （DNS 48ms + TCP 159ms + TLS 296ms，本机到 api.github.com）。仓库页一次要发 4 个请求，
/// 串行 + 每次重建连接 ≈ 3.4s，共享连接池后握手只付一次。
///
/// 同时启用 HTTP/2（Cargo 的 `http2` feature）—— 多个并行请求可复用一个连接多路复用。
pub(crate) fn shared_http() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            // 超时兜底：避免请求永久挂起（README/列表加载卡死根因）
            .timeout(Duration::from_secs(15))
            // 连接池：并行加载最多同时用到 4~6 个连接，留出余量
            .pool_max_idle_per_host(8)
            // 空闲连接保留 90s：列表→详情→切 tab 的连续操作都能命中同一连接
            .pool_idle_timeout(Duration::from_secs(90))
            .tcp_nodelay(true)
            .tcp_keepalive(Duration::from_secs(60))
            .build()
            .expect("构建 reqwest 客户端失败")
    })
}

/// API 客户端：持有 base host 与 access token（HTTP 连接由 [shared_http] 复用）
#[derive(Debug, Clone)]
pub struct ApiClient {
    pub host: String,
    pub token: String,
    http: reqwest::Client,
}

impl ApiClient {
    pub fn new(host: impl Into<String>, token: impl Into<String>) -> Self {
        Self {
            host: host.into(),
            token: token.into(),
            // reqwest::Client 内部是 Arc，clone 只增加引用计数，不会新建连接池
            http: shared_http().clone(),
        }
    }

    /// 构造 API 基础 URL（数据请求走 api.github.com，OAuth 端点才走 github.com）
    pub fn base_url(&self) -> String {
        if self.host == "github.com" {
            "https://api.github.com/".to_string()
        } else {
            format!("https://{}/api/v3/", self.host)
        }
    }

    /// 带鉴权的 GET 请求，返回 JSON 字符串
    pub async fn get_json(&self, path: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .get(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 GET 请求（自定义 Accept），返回 JSON 字符串
    pub async fn get_json_with_accept(&self, path: &str, accept: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .get(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", accept)
            .header("User-Agent", "Branchbase/0.1")
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 POST 请求，返回 JSON 字符串
    pub async fn post_json(&self, path: &str, body: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .post(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .body(body.to_string())
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 POST（`Authorization: bearer`，用于 GraphQL v4 端点）
    ///
    /// 与 [post_json] 只差鉴权前缀：REST 用 `token`，GraphQL 文档要求 `bearer`。
    pub async fn post_bearer_json(&self, path: &str, body: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .post(&url)
            .header("Authorization", format!("bearer {}", self.token))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .body(body.to_string())
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 PUT 请求，返回 JSON 字符串（用于更新/新建文件）
    pub async fn put_json(&self, path: &str, body: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .put(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .body(body.to_string())
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 PATCH 请求，返回 JSON 字符串（用于标记通知已读等）
    pub async fn patch_json(&self, path: &str, body: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .patch(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .body(body.to_string())
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 PUT 请求（无 body，用于标记全部通知已读等），返回 JSON 字符串
    pub async fn put_empty(&self, path: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .put(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 带鉴权的 PATCH 请求（无 body，用于标记单条通知已读等），返回 JSON 字符串
    pub async fn patch_empty(&self, path: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .patch(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    /// 下载任意 URL 的文本内容（公开资源，不带鉴权，如 release 附件）
    /// 带鉴权的 DELETE 请求（删除分支/仓库等），返回 JSON 字符串或空串
    pub async fn delete_json(&self, path: &str) -> Result<String> {
        let url = format!("{}{}", self.base_url(), path.trim_start_matches('/'));
        let resp = self
            .http
            .delete(&url)
            .header("Authorization", format!("token {}", self.token))
            .header("Accept", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }

    pub async fn get_raw_text(&self, url: &str) -> Result<String> {
        let resp = self
            .http
            .get(url)
            .header("User-Agent", "Branchbase/0.1")
            .send()
            .await?;
        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Other(format!("HTTP {status}: {text}")));
        }
        Ok(text)
    }
}