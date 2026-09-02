//! Token 管理：存储、校验、刷新
//!
//! 注意：token 的加密落盘由 Android 端（Keystore / EncryptedSharedPreferences）
//! 负责，本模块只提供纯内存态的会话管理与校验逻辑。

use crate::error::{CoreError, Result};
use crate::models::{Session, Token};

/// Token 存储（内存态，持久化由调用方完成）
#[derive(Default)]
pub struct TokenStore {
    sessions: Vec<Session>,
}

impl TokenStore {
    pub fn new() -> Self {
        Self::default()
    }

    /// 保存会话
    pub fn save(&mut self, session: Session) {
        // 按 host 去重
        self.sessions.retain(|s| s.host != session.host);
        self.sessions.push(session);
    }

    /// 获取指定 host 的会话
    pub fn get(&self, host: &str) -> Option<&Session> {
        self.sessions.iter().find(|s| s.host == host)
    }

    /// 移除会话（登出）
    pub fn remove(&mut self, host: &str) {
        self.sessions.retain(|s| s.host != host);
    }

    /// 校验 token 是否有效（调用 /user，能取到用户即有效）
    pub async fn validate(&self, token: &Token, host: &str) -> Result<bool> {
        let endpoint = format!("https://{host}/user");
        let client = reqwest::Client::new();
        let resp = client
            .get(&endpoint)
            .header("Authorization", format!("token {}", token.access_token))
            .send()
            .await?;
        Ok(resp.status().is_success())
    }

    /// 解析序列化的会话 JSON（供 JNI 层传入）
    pub fn from_json(json: &str) -> Result<Session> {
        serde_json::from_str(json).map_err(CoreError::from)
    }

    /// 序列化会话为 JSON（供 JNI 层返回）
    pub fn to_json(session: &Session) -> Result<String> {
        serde_json::to_string(session).map_err(CoreError::from)
    }
}