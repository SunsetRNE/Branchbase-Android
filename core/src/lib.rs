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
pub mod workflow;
pub mod models;
pub mod translate;

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

        /// 带**稳定码**的错误：`code` 进 JNI 串（`ERROR:<CODE>:<message>`），
        /// `message` 是给人看的旧文案（Display 与日志锚点**保持不变**）。
        ///
        /// 为什么单独一个变体而不是改 `Other`：JNI 边界要能区分「有码」与「还没换码」，
        /// 后者仍按老格式原样透出（迁移期双读，见 `docs/specs/i18n-migration.md` §14）。
        #[error("{message}")]
        Coded { code: String, message: String },

        #[error("未知错误: {0}")]
        Other(String),
    }

    impl CoreError {
        /// HTTP 非 2xx：码为 `HTTP_<status>`，文案沿用旧写法（`未知错误: HTTP 404: {body}`）。
        ///
        /// 文案保留「未知错误: 」前缀是为了**日志锚点不漂移**：真机日志与若干测试
        /// 按这串中文定位问题（见 `i18n-migration.md` §7）。界面侧不再显示它 ——
        /// Kotlin 的 `EngineError` 会按码换成一条本地化文案，`message` 只进日志。
        pub fn http(status: u16, body: impl AsRef<str>) -> Self {
            let body = body.as_ref();
            Self::Coded {
                code: format!("HTTP_{status}"),
                message: format!("未知错误: HTTP {status}: {body}"),
            }
        }

        /// JNI 边界用的稳定码；`None` = 这个错误还没换码（按旧格式原样透出）。
        ///
        /// 传输层失败（没发出去 / 没回来）统一记 `NETWORK`：Kotlin 侧只有一条「检查网络、
        /// 代理或证书」的文案，不需要区分 reqwest 的十几种底层原因。
        pub fn code(&self) -> Option<&str> {
            match self {
                CoreError::Coded { code, .. } => Some(code),
                CoreError::Http(_) => Some("NETWORK"),
                _ => None,
            }
        }
    }

    pub type Result<T> = std::result::Result<T, CoreError>;

    #[cfg(test)]
    mod tests {
        use super::CoreError;

        /// HTTP 非 2xx 必须带 `HTTP_<status>` 稳定码，且**文案不变**（日志锚点）。
        #[test]
        fn http_error_carries_stable_code_and_legacy_text() {
            let e = CoreError::http(404, r#"{"message":"Not Found"}"#);
            assert_eq!(Some("HTTP_404"), e.code());
            assert_eq!(
                "未知错误: HTTP 404: {\"message\":\"Not Found\"}",
                e.to_string()
            );
        }

        /// 还没换码的错误不许被硬塞一个码（Kotlin 侧按原文透出）。
        #[test]
        fn uncoded_error_has_no_code() {
            assert_eq!(None, CoreError::Other("不是 git 仓库".into()).code());
            assert_eq!(None, CoreError::Auth("认证失败".into()).code());
        }
    }
}
