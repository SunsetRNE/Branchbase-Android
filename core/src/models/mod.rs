//! 数据模型（对齐 GitHub REST API 响应结构，字段为 snake_case）

use serde::{Deserialize, Serialize};

/// 用户信息（对应 `GET /user`）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct User {
    pub login: String,
    pub id: i64,
    pub avatar_url: String,
    pub name: Option<String>,
    pub email: Option<String>,
    pub bio: Option<String>,
    pub company: Option<String>,
    pub location: Option<String>,
    pub public_repos: i64,
    pub followers: i64,
    pub following: i64,
}

/// 仓库信息（对应 `GET /user/repos` 元素）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Repository {
    pub id: i64,
    pub name: String,
    pub full_name: String,
    pub private: bool,
    pub description: Option<String>,
    pub html_url: String,
    pub language: Option<String>,
    pub stargazers_count: i64,
    pub forks_count: i64,
}

/// 软件包信息（对应 `GET /user/packages` 元素）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Package {
    pub id: i64,
    pub name: String,
    pub package_type: String,
    #[serde(default)]
    pub visibility: Option<String>,
    #[serde(default)]
    pub version_count: Option<i64>,
}

/// 项目信息（对应 `GET /user/projects` 元素，Projects classic）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Project {
    pub id: i64,
    pub name: String,
    #[serde(default)]
    pub body: Option<String>,
    pub number: i64,
    #[serde(default)]
    pub state: Option<String>,
    #[serde(default)]
    pub updated_at: Option<String>,
}

/// OAuth 令牌
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Token {
    pub access_token: String,
    pub token_type: String,
    pub scope: Option<String>,
    /// 刷新令牌（expiring tokens 时返回，用于续期）
    #[serde(default)]
    pub refresh_token: Option<String>,
    /// 过期时间（秒）
    #[serde(default)]
    pub expires_in: Option<i64>,
}

/// 登录会话（持久化到本地加密存储）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Session {
    pub token: Token,
    pub user: User,
    /// 登录的主机（github.com 或 GHE 域名）
    pub host: String,
}

/// 2FA 验证请求
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TwoFactorRequest {
    pub code: String,
    /// "app"（TOTP）或 "sms"
    pub factor_type: String,
}

/// OAuth 授权配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OAuthConfig {
    pub client_id: String,
    pub client_secret: String,
    pub redirect_uri: String,
    /// github.com 或 GHE 域名
    pub host: String,
    pub scopes: Vec<String>,
}