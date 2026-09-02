//! Branchbase 业务处理核心（Rust）
//!
//! 架构分层：
//! - `models`  : 数据模型（User / Repository / Token ...）
//! - `auth`    : 认证逻辑（OAuth 授权码 + PKCE、token 管理、2FA）
//! - `api`     : GitHub API 客户端（REST / GraphQL 通道）
//! - `bridge`  : JNI 桥接层（暴露给 Android Compose 层调用）
//!
//! 编译为 `libbranchbase_core.so`，由 Android 端 `System.loadLibrary` 加载。

pub mod api;
pub mod auth;
pub mod bridge;
pub mod git;
pub mod html;
pub mod models;

/// 库的版本号，供 JNI 层校验 ABI 兼容性
pub const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");

/// 统一的错误类型
pub mod error {
    use thiserror::Error;

    #[derive(Debug, Error)]
    pub enum CoreError {
        #[error("HTTP 请求失败: {0}")]
        Http(#[from] reqwest::Error),

        #[error("JSON 解析失败: {0}")]
        Json(#[from] serde_json::Error),

        #[error("认证失败: {0}")]
        Auth(String),

        #[error("参数无效: {0}")]
        InvalidArgument(String),

        #[error("未知错误: {0}")]
        Other(String),
    }

    pub type Result<T> = std::result::Result<T, CoreError>;
}
