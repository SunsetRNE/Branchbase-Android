//! 底层 HTTP 客户端（封装 reqwest，统一注入 token 与请求头）

use crate::error::{CoreError, Result};

/// API 客户端：持有 base host 与 access token
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
            // 超时兜底：避免请求永久挂起（README/列表加载卡死根因）
            http: reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(15))
                .build()
                .expect("构建 reqwest 客户端失败"),
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

    /// 下载任意 URL 的文本内容（公开资源，不带鉴权，如 release 附件）
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