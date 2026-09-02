//! OAuth 2.0 授权码 + PKCE 流程
//!
//! 流程：生成 PKCE challenge → 跳转授权页 → 回调拿 code → 交换 token

use crate::error::{CoreError, Result};
use crate::models::{OAuthConfig, Session, Token, User};

use rand::RngCore;
use sha2::{Digest, Sha256};
use url::Url;

/// PKCE 参数对：verifier（明文，本地保留）+ challenge（传给授权服务器）
#[derive(Debug, Clone)]
pub struct PkcePair {
    pub verifier: String,
    pub challenge: String,
}

impl PkcePair {
    /// 生成新的 PKCE 参数对（S256 方式）
    pub fn generate() -> Self {
        // 生成 64 字节随机数，base64url 编码为 verifier
        let mut bytes = [0u8; 64];
        rand::thread_rng().fill_bytes(&mut bytes);
        let verifier = base64_url(&bytes);

        // challenge = base64url(sha256(verifier))
        let challenge = {
            let digest = Sha256::digest(verifier.as_bytes());
            base64_url(&digest)
        };

        Self { verifier, challenge }
    }
}

/// OAuth 客户端
#[derive(Debug, Clone)]
pub struct OAuthClient {
    config: OAuthConfig,
}

impl OAuthClient {
    pub fn new(config: OAuthConfig) -> Self {
        Self { config }
    }

    /// 构建授权跳转 URL（`GET /login/oauth/authorize`）
    /// challenge 由调用方通过 `PkcePair::generate()` 生成后传入，确保与 verifier 配对
    pub fn authorize_url(&self, challenge: &str) -> Result<String> {
        let base = format!("https://{}/login/oauth/authorize", self.config.host);

        let mut url = Url::parse(&base).map_err(|e| CoreError::Other(e.to_string()))?;
        url.query_pairs_mut()
            .append_pair("client_id", &self.config.client_id)
            .append_pair("redirect_uri", &self.config.redirect_uri)
            .append_pair("scope", &self.config.scopes.join(" "))
            .append_pair("state", &rand_state())
            .append_pair("code_challenge", challenge)
            .append_pair("code_challenge_method", "S256");

        Ok(url.to_string())
    }

    /// 用授权码交换 access token（`POST /login/oauth/access_token`）
    pub async fn exchange_code(&self, code: &str, verifier: &str) -> Result<Session> {
        let endpoint = format!("https://{}/login/oauth/access_token", self.config.host);

        let client = reqwest::Client::new();
        let resp = client
            .post(&endpoint)
            .header("Accept", "application/json")
            .form(&[
                ("client_id", self.config.client_id.as_str()),
                ("client_secret", self.config.client_secret.as_str()),
                ("redirect_uri", self.config.redirect_uri.as_str()),
                ("code", code),
                ("code_verifier", verifier),
            ])
            .send()
            .await?;

        let status = resp.status();
        let text = resp.text().await?;

        // GitHub 成功返回 {"access_token":...}，失败返回 {"error":"...","error_description":"..."}
        if !status.is_success() || text.contains("\"error\"") {
            return Err(CoreError::Auth(format!("GitHub 错误 ({status}): {text}")));
        }

        let token: Token = serde_json::from_str(&text)?;

        // 拉取用户信息
        let user = self.fetch_user(&token.access_token).await?;

        Ok(Session {
            token,
            user,
            host: self.config.host.clone(),
        })
    }

    /// 拉取当前用户信息（`GET /user`）
    async fn fetch_user(&self, access_token: &str) -> Result<User> {
        // API 数据请求走 api.github.com（OAuth 端点才走 github.com）
        let api_base = if self.config.host == "github.com" {
            "https://api.github.com".to_string()
        } else {
            format!("https://{}/api/v3", self.config.host)
        };
        let endpoint = format!("{api_base}/user");
        let client = reqwest::Client::new();
        let resp = client
            .get(&endpoint)
            .header("Authorization", format!("token {access_token}"))
            .header("Accept", "application/json")
            .header("User-Agent", "Branchbase/0.1")
            .send()
            .await?;

        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            return Err(CoreError::Auth(format!("获取用户失败 ({status}): {text}")));
        }

        let user: User = serde_json::from_str(&text)?;
        Ok(user)
    }

    /// 用 refresh token 刷新 access token（滚动续期，返回新 token 含新 refresh token）
    pub async fn refresh_token(&self, refresh_token: &str) -> Result<Token> {
        let endpoint = format!("https://{}/login/oauth/access_token", self.config.host);
        let client = reqwest::Client::new();
        let resp = client
            .post(&endpoint)
            .header("Accept", "application/json")
            .form(&[
                ("client_id", self.config.client_id.as_str()),
                ("client_secret", self.config.client_secret.as_str()),
                ("grant_type", "refresh_token"),
                ("refresh_token", refresh_token),
            ])
            .send()
            .await?;

        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() || text.contains("\"error\"") {
            return Err(CoreError::Auth(format!("刷新失败 ({status}): {text}")));
        }

        let token: Token = serde_json::from_str(&text)?;
        Ok(token)
    }
}

/// base64url 编码（无填充）
fn base64_url(bytes: &[u8]) -> String {
    use base64::engine::general_purpose::URL_SAFE_NO_PAD;
    use base64::Engine;
    URL_SAFE_NO_PAD.encode(bytes)
}

/// 生成随机 state（防 CSRF）
fn rand_state() -> String {
    let mut bytes = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut bytes);
    base64_url(&bytes)
}