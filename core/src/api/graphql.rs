//! GitHub GraphQL（v4）通道。
//!
//! 与 REST 的差别：
//! - 单一端点 `POST /graphql`，请求体是 `{query, variables}`，鉴权用 `bearer` 前缀
//! - 业务错误可能出现在 HTTP 200 的 `errors` 数组里，这里统一归一为 `CoreError`
//!
//! 首个用途：**贡献日历**。REST 的 `/users/{login}/events` 只有近 90 天、最多 300 条
//! 且仅公开事件，画不出 52 周的贡献墙；GraphQL 的 `contributionCalendar` 一次即可拿到。

use crate::api::ApiClient;
use crate::error::{CoreError, Result};
use serde_json::json;

/// 贡献日历查询（与 GitHub 官网同源）。
///
/// `from` / `to` 为 ISO8601（如 `2025-09-08T00:00:00Z`），GitHub 要求跨度不超过一年。
const CONTRIBUTION_QUERY: &str = r#"
query($login: String!, $from: DateTime!, $to: DateTime!) {
  user(login: $login) {
    contributionsCollection(from: $from, to: $to) {
      totalCommitContributions
      totalIssueContributions
      totalPullRequestContributions
      totalPullRequestReviewContributions
      contributionCalendar {
        totalContributions
        weeks {
          contributionDays {
            date
            contributionCount
            color
          }
        }
      }
    }
  }
}
"#;

/// GraphQL 门面（复用同一个 [ApiClient]，仅鉴权前缀不同）
pub struct GraphQLApi {
    client: ApiClient,
}

impl GraphQLApi {
    pub fn new(client: ApiClient) -> Self {
        Self { client }
    }

    /// 执行任意 GraphQL 查询，返回 `data` 部分的 JSON 字符串。
    ///
    /// 便于后续复用：调用方只需传 query 与 variables，错误统一透出。
    pub async fn query(&self, query: &str, variables: serde_json::Value) -> Result<String> {
        let body = json!({ "query": query, "variables": variables }).to_string();
        let text = self.client.post_bearer_json("/graphql", &body).await?;
        let parsed: serde_json::Value = serde_json::from_str(&text)?;
        if let Some(errors) = parsed.get("errors") {
            if !errors.is_null() {
                return Err(CoreError::Other(format!("GraphQL: {errors}")));
            }
        }
        parsed
            .get("data")
            .filter(|d| !d.is_null())
            .map(|d| d.to_string())
            .ok_or_else(|| CoreError::Other("GraphQL: 响应缺少 data 字段".to_string()))
    }

    /// 贡献日历：返回 `user.contributionsCollection` 的 JSON（含 `contributionCalendar`）。
    ///
    /// @param login GitHub 登录名
    /// @param from 起始时间（ISO8601，含）
    /// @param to 结束时间（ISO8601，含）
    pub async fn contribution_calendar(&self, login: &str, from: &str, to: &str) -> Result<String> {
        let vars = json!({ "login": login, "from": from, "to": to });
        self.query(CONTRIBUTION_QUERY, vars).await
    }
}
