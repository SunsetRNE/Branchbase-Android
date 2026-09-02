//! 双因子认证（2FA）验证
//!
//! GitHub 的 2FA 在 OAuth 授权页由服务端完成，本模块主要负责：
//! 1. 校验验证码格式（6 位数字）
//! 2. 预留 TOTP 计算能力（用于 GHE 的本地 TOTP 场景）

use crate::error::{CoreError, Result};
use crate::models::TwoFactorRequest;

/// 2FA 验证器
#[derive(Default)]
pub struct TwoFactorVerifier;

impl TwoFactorVerifier {
    pub fn new() -> Self {
        Self
    }

    /// 校验验证码格式（6 位纯数字）
    pub fn validate_format(code: &str) -> Result<()> {
        if code.len() != 6 || !code.chars().all(|c| c.is_ascii_digit()) {
            return Err(CoreError::InvalidArgument(
                "验证码必须为 6 位数字".to_string(),
            ));
        }
        Ok(())
    }

    /// 校验请求（格式 + 类型）
    pub fn validate_request(&self, req: &TwoFactorRequest) -> Result<()> {
        Self::validate_format(&req.code)?;
        match req.factor_type.as_str() {
            "app" | "sms" => Ok(()),
            other => Err(CoreError::InvalidArgument(format!(
                "不支持的 2FA 类型: {other}"
            ))),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_valid_code() {
        assert!(TwoFactorVerifier::validate_format("123456").is_ok());
    }

    #[test]
    fn test_invalid_code() {
        assert!(TwoFactorVerifier::validate_format("12345").is_err());
        assert!(TwoFactorVerifier::validate_format("12345a").is_err());
    }
}