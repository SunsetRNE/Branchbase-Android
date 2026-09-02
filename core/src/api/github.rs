//! GitHub API 业务方法（基于 ApiClient）

use crate::api::ApiClient;
use crate::error::Result;
use crate::models::{Branch, Package, Project, Repository, User};

/// GitHub API 门面
pub struct GitHubApi {
    client: ApiClient,
}

impl GitHubApi {
    pub fn new(client: ApiClient) -> Self {
        Self { client }
    }

    /// 获取当前用户（`GET /user`）
    pub async fn me(&self) -> Result<User> {
        let json = self.client.get_json("/user").await?;
        let user: User = serde_json::from_str(&json)?;
        Ok(user)
    }

    /// 获取指定用户（`GET /users/{login}`）
    pub async fn user(&self, login: &str) -> Result<User> {
        let path = format!("/users/{login}");
        let json = self.client.get_json(&path).await?;
        let user: User = serde_json::from_str(&json)?;
        Ok(user)
    }

    /// 获取当前用户仓库列表（`GET /user/repos`）
    pub async fn my_repos(&self) -> Result<Vec<Repository>> {
        let json = self.client.get_json("/user/repos").await?;
        let repos: Vec<Repository> = serde_json::from_str(&json)?;
        Ok(repos)
    }

    /// 获取当前用户星标的仓库（`GET /user/starred`）
    pub async fn starred_repos(&self) -> Result<Vec<Repository>> {
        let json = self.client.get_json("/user/starred").await?;
        let repos: Vec<Repository> = serde_json::from_str(&json)?;
        Ok(repos)
    }

    /// 获取用户收到的事件（`GET /users/{login}/received_events`，返回原始 JSON）
    pub async fn received_events(&self, login: &str) -> Result<String> {
        let path = format!("/users/{login}/received_events");
        self.client.get_json(&path).await
    }

    /// 获取当前用户软件包（`GET /user/packages`，需 read:packages 权限）
    pub async fn my_packages(&self) -> Result<Vec<Package>> {
        let json = self.client.get_json("/user/packages").await?;
        let packages: Vec<Package> = serde_json::from_str(&json)?;
        Ok(packages)
    }

    /// 获取当前用户项目（`GET /user/projects`，Projects classic，需 repo 权限）
    pub async fn my_projects(&self) -> Result<Vec<Project>> {
        let json = self.client.get_json("/user/projects").await?;
        let projects: Vec<Project> = serde_json::from_str(&json)?;
        Ok(projects)
    }

    /// 搜索仓库（`GET /search/repositories`，返回原始 JSON 含 total_count + items）
    pub async fn search_repositories(&self, query: &str, sort: Option<&str>) -> Result<String> {
        let encoded: String = url::form_urlencoded::byte_serialize(query.as_bytes()).collect();
        let mut path = format!("/search/repositories?q={encoded}");
        if let Some(s) = sort {
            path = format!("{path}&sort={s}");
        }
        self.client.get_json(&path).await
    }

    /// 搜索用户（`GET /search/users`）
    pub async fn search_users(&self, query: &str) -> Result<String> {
        let encoded: String = url::form_urlencoded::byte_serialize(query.as_bytes()).collect();
        let path = format!("/search/users?q={encoded}");
        self.client.get_json(&path).await
    }

    /// 搜索 issues/PR（`GET /search/issues`）
    pub async fn search_issues(&self, query: &str) -> Result<String> {
        let encoded: String = url::form_urlencoded::byte_serialize(query.as_bytes()).collect();
        let path = format!("/search/issues?q={encoded}");
        self.client.get_json(&path).await
    }

    /// 搜索代码（`GET /search/code`，用 text-match header 返回代码片段）
    pub async fn search_code(&self, query: &str) -> Result<String> {
        let encoded: String = url::form_urlencoded::byte_serialize(query.as_bytes()).collect();
        let path = format!("/search/code?q={encoded}");
        self.client
            .get_json_with_accept(&path, "application/vnd.github.text-match+json")
            .await
    }

    /// 搜索提交（`GET /search/commits`，返回原始 JSON 含 total_count + items）
    pub async fn search_commits(&self, query: &str) -> Result<String> {
        let encoded: String = url::form_urlencoded::byte_serialize(query.as_bytes()).collect();
        let path = format!("/search/commits?q={encoded}");
        self.client
            .get_json_with_accept(&path, "application/vnd.github.cloak-preview+json")
            .await
    }

    /// 搜索主题（`GET /search/topics`，返回原始 JSON 含 total_count + items）
    pub async fn search_topics(&self, query: &str) -> Result<String> {
        let encoded: String = url::form_urlencoded::byte_serialize(query.as_bytes()).collect();
        let path = format!("/search/topics?q={encoded}");
        self.client
            .get_json_with_accept(&path, "application/vnd.github.mercy-preview+json")
            .await
    }

    /// 获取指定用户的公开仓库（`GET /users/{login}/repos`）
    pub async fn user_repos(&self, login: &str) -> Result<Vec<Repository>> {
        let path = format!("/users/{login}/repos");
        let json = self.client.get_json(&path).await?;
        let repos: Vec<Repository> = serde_json::from_str(&json)?;
        Ok(repos)
    }

    /// 获取仓库 README 的渲染后 HTML（`GET /repos/{owner}/{repo}/readme`）
    ///
    /// 用 `Accept: application/vnd.github.html+json` 拿 markdown 渲染结果。
    /// 注意：该 media type 的响应 body 就是渲染后的 HTML 文本（非 JSON，无 base64 content 字段），
    /// 直接返回 HTML 字符串，交由 Compose 侧 parseHtml 解析。
    /// 无 README 时返回 `Err`（HTTP 404）。
    pub async fn readme_html(&self, owner: &str, repo: &str, branch: &str) -> Result<String> {
        // 分支透传：指定 ref（默认分支或用户选择的分支）；空串则用仓库默认分支
        let path = if branch.is_empty() {
            format!("/repos/{owner}/{repo}/readme")
        } else {
            // 分支名可能含 `/`（如 feature/html-parser），需 URL 编码
            let encoded: String = url::form_urlencoded::byte_serialize(branch.as_bytes()).collect();
            format!("/repos/{owner}/{repo}/readme?ref={encoded}")
        };
        self.client
            .get_json_with_accept(&path, "application/vnd.github.html+json")
            .await
    }

    /// 获取仓库分支列表（`GET /repos/{owner}/{repo}/branches`，返回分支名 + 是否保护）
    pub async fn list_branches(&self, owner: &str, repo: &str) -> Result<Vec<Branch>> {
        let path = format!("/repos/{owner}/{repo}/branches");
        let json = self.client.get_json(&path).await?;
        let branches: Vec<Branch> = serde_json::from_str(&json)?;
        Ok(branches)
    }

    /// 获取单个仓库信息（`GET /repos/{owner}/{repo}`，返回原始 JSON）
    pub async fn repo_info(&self, owner: &str, repo: &str) -> Result<String> {
        let path = format!("/repos/{owner}/{repo}");
        self.client.get_json(&path).await
    }

    /// 获取仓库语言统计（`GET /repos/{owner}/{repo}/languages`，返回 {语言: 字节数} JSON 对象）
    pub async fn repo_languages(&self, owner: &str, repo: &str) -> Result<String> {
        let path = format!("/repos/{owner}/{repo}/languages");
        self.client.get_json(&path).await
    }

    /// 获取仓库贡献者（`GET /repos/{owner}/{repo}/contributors`，返回 JSON 数组）
    pub async fn repo_contributors(&self, owner: &str, repo: &str) -> Result<String> {
        let path = format!("/repos/{owner}/{repo}/contributors");
        self.client.get_json(&path).await
    }

    /// 将 markdown 渲染为 HTML（`POST /markdown`，GFM 模式）
    pub async fn render_markdown(&self, text: &str) -> Result<String> {
        let body = serde_json::json!({ "text": text, "mode": "gfm" }).to_string();
        self.client.post_json("/markdown", &body).await
    }

    /// 更新/新建单个文件（`PUT /repos/{o}/{r}/contents/{path}`，单文件提交）
    ///
    /// - `content`：新文件内容（文本）。
    /// - `sha`：当前文件 blob sha（更新已有文件时必需；新建文件传空）。
    /// - `branch`：目标分支。
    pub async fn put_contents(
        &self,
        owner: &str,
        repo: &str,
        path: &str,
        message: &str,
        content: &str,
        sha: &str,
        branch: &str,
    ) -> Result<String> {
        use base64::Engine;
        let content_b64 = base64::engine::general_purpose::STANDARD.encode(content.as_bytes());
        let mut body = serde_json::json!({
            "message": message,
            "content": content_b64,
            "branch": branch,
        });
        if !sha.is_empty() {
            body["sha"] = serde_json::json!(sha);
        }
        let path = format!("/repos/{owner}/{repo}/contents/{path}");
        self.client.put_json(&path, &body.to_string()).await
    }

    /// 拉取 latest release 的 signature.txt 校验文件内容。
    ///
    /// 1. `GET /repos/{owner}/{repo}/releases/latest` 拿到 assets；
    /// 2. 找到 name = "signature.txt" 的 browser_download_url；
    /// 3. 下载并返回其文本内容。
    pub async fn latest_release_signature(&self, owner: &str, repo: &str) -> Result<String> {
        let path = format!("/repos/{owner}/{repo}/releases/latest");
        let json = self.client.get_json(&path).await?;
        let value: serde_json::Value = serde_json::from_str(&json)?;
        let url = value
            .get("assets")
            .and_then(|a| a.as_array())
            .and_then(|assets| {
                assets.iter().find_map(|asset| {
                    if asset.get("name").and_then(|n| n.as_str()) == Some("signature.txt") {
                        asset
                            .get("browser_download_url")
                            .and_then(|u| u.as_str())
                            .map(|s| s.to_string())
                    } else {
                        None
                    }
                })
            })
            .ok_or_else(|| crate::error::CoreError::Other("latest release 无 signature.txt 附件".into()))?;
        self.client.get_raw_text(&url).await
    }

    /// 拉取仓库 verify/signature.txt 校验文件内容（contents API，base64 解码）。
    pub async fn repo_signature(&self, owner: &str, repo: &str) -> Result<String> {
        use base64::Engine;
        let path = format!("/repos/{owner}/{repo}/contents/verify/signature.txt");
        let json = self.client.get_json(&path).await?;
        let value: serde_json::Value = serde_json::from_str(&json)?;
        let content = value
            .get("content")
            .and_then(|c| c.as_str())
            .ok_or_else(|| crate::error::CoreError::Other("signature.txt 无 content 字段".into()))?;
        let decoded = base64::engine::general_purpose::STANDARD
            .decode(content.replace('\n', "").as_bytes())
            .map_err(|e| crate::error::CoreError::Other(format!("signature.txt base64 解码失败: {e}")))?;
        String::from_utf8(decoded)
            .map_err(|e| crate::error::CoreError::Other(format!("signature.txt 非 UTF-8: {e}")))
    }

    /// 标记单条通知已读（`PATCH /notifications/threads/{id}`）
    pub async fn mark_notification_read(&self, thread_id: &str) -> Result<String> {
        let path = format!("/notifications/threads/{thread_id}");
        self.client.patch_json(&path, "{}").await
    }

    /// 标记全部通知已读（`PUT /notifications`）
    pub async fn mark_all_notifications_read(&self) -> Result<String> {
        self.client.put_json("/notifications", "{}").await
    }
}