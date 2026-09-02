//! 认证模块：OAuth 授权码 + PKCE 流程、token 管理、2FA 验证

pub mod oauth;
pub mod token;
pub mod twofactor;

pub use oauth::{OAuthClient, PkcePair};
pub use token::TokenStore;
pub use twofactor::TwoFactorVerifier;

use crate::error::Result;
use crate::models::{OAuthConfig, Session};

/// 认证门面：对外暴露统一的认证流程入口
pub struct AuthManager {
    pub oauth: OAuthClient,
    pub tokens: TokenStore,
    pub twofactor: TwoFactorVerifier,
}

impl AuthManager {
    /// 用默认配置构造（github.com 宿主）
    pub fn github(config: OAuthConfig) -> Self {
        Self {
            oauth: OAuthClient::new(config),
            tokens: TokenStore::default(),
            twofactor: TwoFactorVerifier::default(),
        }
    }

    /// 生成授权跳转 URL（challenge 由调用方传入，确保与 verifier 配对）
    pub fn authorize_url(&self, challenge: &str) -> Result<String> {
        self.oauth.authorize_url(challenge)
    }

    /// 用授权码交换 token，并拉取用户信息，构建会话
    pub async fn exchange_code(&self, code: &str, verifier: &str) -> Result<Session> {
        self.oauth.exchange_code(code, verifier).await
    }
}