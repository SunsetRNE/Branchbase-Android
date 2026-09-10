//! GitHub API 业务方法（基于 ApiClient）

use crate::api::ApiClient;
use crate::error::{CoreError, Result};
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

    /// 批量提交多个文件（Git Data API：blobs → tree → commit → 更新 ref）。
    ///
    /// 用于「多文件模式」：一次提交包含多个文件改动，只产生**一个** commit，
    /// 而不是 [Self::put_contents] 那种每个文件一个 commit。
    ///
    /// 步骤（对齐 GitHub 官方 Git Data 流程）：
    /// 1. `GET /git/ref/heads/{branch}` 取分支 HEAD commit
    /// 2. `GET /git/commits/{sha}` 取基线 tree
    /// 3. 每个文件 `POST /git/blobs`（base64）
    /// 4. `POST /git/trees`（`base_tree` + 新增/覆盖条目）得新 tree
    /// 5. `POST /git/commits`（parents = 基线 commit）
    /// 6. `PATCH /git/refs/heads/{branch}` 指向新 commit
    ///
    /// @param files `(仓库内相对路径, 新内容)` 列表，非空
    /// @return 新 commit 的 sha
    pub async fn commit_files(
        &self,
        owner: &str,
        repo: &str,
        branch: &str,
        message: &str,
        files: &[(String, String)],
    ) -> Result<String> {
        use base64::Engine;
        if files.is_empty() {
            return Err(CoreError::Other("没有要提交的文件".into()));
        }

        // 1) 分支 HEAD
        let ref_json = self
            .client
            .get_json(&format!("/repos/{owner}/{repo}/git/ref/heads/{branch}"))
            .await?;
        let ref_value: serde_json::Value = serde_json::from_str(&ref_json)?;
        let base_commit = ref_value
            .get("object")
            .and_then(|o| o.get("sha"))
            .and_then(|s| s.as_str())
            .ok_or_else(|| CoreError::Other("读取分支 ref 失败：缺少 object.sha".into()))?
            .to_string();

        // 2) 基线 tree
        let commit_json = self
            .client
            .get_json(&format!("/repos/{owner}/{repo}/git/commits/{base_commit}"))
            .await?;
        let commit_value: serde_json::Value = serde_json::from_str(&commit_json)?;
        let base_tree = commit_value
            .get("tree")
            .and_then(|t| t.get("sha"))
            .and_then(|s| s.as_str())
            .ok_or_else(|| CoreError::Other("读取基线 tree 失败".into()))?
            .to_string();

        // 3) 每个文件一个 blob
        let mut tree_entries = Vec::with_capacity(files.len());
        for (path, content) in files {
            let blob_body = serde_json::json!({
                "content": base64::engine::general_purpose::STANDARD.encode(content.as_bytes()),
                "encoding": "base64",
            });
            let blob_json = self
                .client
                .post_json(&format!("/repos/{owner}/{repo}/git/blobs"), &blob_body.to_string())
                .await?;
            let blob_value: serde_json::Value = serde_json::from_str(&blob_json)?;
            let blob_sha = blob_value
                .get("sha")
                .and_then(|s| s.as_str())
                .ok_or_else(|| CoreError::Other(format!("创建 blob 失败: {path}")))?
                .to_string();
            tree_entries.push(serde_json::json!({
                "path": path,
                "mode": "100644",
                "type": "blob",
                "sha": blob_sha,
            }));
        }

        // 4) 新 tree（基于基线 tree，只覆盖本次涉及的文件）
        let tree_body = serde_json::json!({ "base_tree": base_tree, "tree": tree_entries });
        let tree_json = self
            .client
            .post_json(&format!("/repos/{owner}/{repo}/git/trees"), &tree_body.to_string())
            .await?;
        let tree_value: serde_json::Value = serde_json::from_str(&tree_json)?;
        let new_tree = tree_value
            .get("sha")
            .and_then(|s| s.as_str())
            .ok_or_else(|| CoreError::Other("创建 tree 失败".into()))?
            .to_string();

        // 5) 新 commit
        let commit_body = serde_json::json!({
            "message": message,
            "tree": new_tree,
            "parents": [base_commit],
        });
        let new_commit_json = self
            .client
            .post_json(&format!("/repos/{owner}/{repo}/git/commits"), &commit_body.to_string())
            .await?;
        let new_commit_value: serde_json::Value = serde_json::from_str(&new_commit_json)?;
        let new_commit = new_commit_value
            .get("sha")
            .and_then(|s| s.as_str())
            .ok_or_else(|| CoreError::Other("创建 commit 失败".into()))?
            .to_string();

        // 6) 移动分支引用
        let update_body = serde_json::json!({ "sha": new_commit });
        self.client
            .patch_json(
                &format!("/repos/{owner}/{repo}/git/refs/heads/{branch}"),
                &update_body.to_string(),
            )
            .await?;

        Ok(new_commit)
    }

    /// 创建 release（`POST /repos/{o}/{r}/releases`）。
    ///
    /// @param draft 草稿（不公开）；@param prerelease 预发布（不占用 latest）
    /// @param target_commitish 目标分支/commit（空串 = 仓库默认分支）
    pub async fn create_release(
        &self,
        owner: &str,
        repo: &str,
        tag: &str,
        name: &str,
        body: &str,
        draft: bool,
        prerelease: bool,
        target_commitish: &str,
    ) -> Result<String> {
        let mut payload = serde_json::json!({
            "tag_name": tag,
            "name": name,
            "body": body,
            "draft": draft,
            "prerelease": prerelease,
        });
        if !target_commitish.trim().is_empty() {
            payload["target_commitish"] = serde_json::json!(target_commitish.trim());
        }
        self.client
            .post_json(&format!("/repos/{owner}/{repo}/releases"), &payload.to_string())
            .await
    }

    /// 编辑 release（`PATCH /repos/{o}/{r}/releases/{id}`）。
    pub async fn update_release(
        &self,
        owner: &str,
        repo: &str,
        id: u64,
        tag: &str,
        name: &str,
        body: &str,
        draft: bool,
        prerelease: bool,
    ) -> Result<String> {
        let payload = serde_json::json!({
            "tag_name": tag,
            "name": name,
            "body": body,
            "draft": draft,
            "prerelease": prerelease,
        });
        self.client
            .patch_json(&format!("/repos/{owner}/{repo}/releases/{id}"), &payload.to_string())
            .await
    }

    /// 删除 release（`DELETE /repos/{o}/{r}/releases/{id}`）。
    pub async fn delete_release(&self, owner: &str, repo: &str, id: u64) -> Result<String> {
        self.client
            .delete_json(&format!("/repos/{owner}/{repo}/releases/{id}"))
            .await
    }

    /// 发表 issue 评论（`POST /repos/{o}/{r}/issues/{number}/comments`）。
    pub async fn create_issue_comment(
        &self,
        owner: &str,
        repo: &str,
        number: u64,
        body: &str,
    ) -> Result<String> {
        let payload = serde_json::json!({ "body": body });
        self.client
            .post_json(
                &format!("/repos/{owner}/{repo}/issues/{number}/comments"),
                &payload.to_string(),
            )
            .await
    }

    /// 修改 issue（`PATCH /repos/{o}/{r}/issues/{number}`）。
    ///
    /// 目前只用于关闭/重新打开：@param state 取 `"open"` / `"closed"`。
    pub async fn update_issue(
        &self,
        owner: &str,
        repo: &str,
        number: u64,
        state: &str,
    ) -> Result<String> {
        let payload = serde_json::json!({ "state": state });
        self.client
            .patch_json(
                &format!("/repos/{owner}/{repo}/issues/{number}"),
                &payload.to_string(),
            )
            .await
    }

    /// 仓库标签列表（`GET /repos/{o}/{r}/labels`），供 issue 按标签筛选。
    pub async fn list_labels(&self, owner: &str, repo: &str) -> Result<String> {
        self.client
            .get_json(&format!("/repos/{owner}/{repo}/labels?per_page=100"))
            .await
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

    /// 标记单条通知已读（`PATCH /notifications/threads/{id}`，无 body）
    pub async fn mark_notification_read(&self, thread_id: &str) -> Result<String> {
        let path = format!("/notifications/threads/{thread_id}");
        self.client.patch_empty(&path).await
    }

    /// 标记全部通知已读（`PUT /notifications`，无 body）
    pub async fn mark_all_notifications_read(&self) -> Result<String> {
        self.client.put_empty("/notifications").await
    }

    // ── 协作与仓库管理（对齐 docs/decision-pages-gap.md §8.4 执行层） ──

    /// 读取分支 ref 的 sha（GET /repos/{o}/{r}/git/ref/heads/{branch}）
    pub async fn get_ref_sha(&self, owner: &str, repo: &str, branch: &str) -> Result<String> {
        let path = format!("/repos/{owner}/{repo}/git/ref/heads/{branch}");
        let json = self.client.get_json(&path).await?;
        let value: serde_json::Value = serde_json::from_str(&json)?;
        value["object"]["sha"]
            .as_str()
            .map(|s| s.to_string())
            .ok_or_else(|| crate::error::CoreError::Other("ref 响应缺少 sha".into()))
    }

    /// 服务端合并分支（`POST /repos/{o}/{r}/merges`）—— 「A 分支同步到 B 分支」。
    ///
    /// - `base` = 目标分支，`head` = 源分支
    /// - 返回合并提交 JSON；**已是最新时返回空串**（GitHub 回 204 No Content）
    /// - 冲突时 GitHub 回 409，会作为错误透出（提示需要人工解决）
    pub async fn merge_branch(
        &self,
        owner: &str,
        repo: &str,
        base: &str,
        head: &str,
        message: &str,
    ) -> Result<String> {
        let mut body = serde_json::json!({ "base": base, "head": head });
        if !message.trim().is_empty() {
            body["commit_message"] = serde_json::json!(message);
        }
        self.client
            .post_json(&format!("/repos/{owner}/{repo}/merges"), &body.to_string())
            .await
    }

    /// 比较两个分支（`GET /repos/{o}/{r}/compare/{base}...{head}`）。
    ///
    /// 返回 JSON 含 `ahead_by` / `behind_by` / `status`，用于同步前的预览。
    pub async fn compare_branches(
        &self,
        owner: &str,
        repo: &str,
        base: &str,
        head: &str,
    ) -> Result<String> {
        self.client
            .get_json(&format!("/repos/{owner}/{repo}/compare/{base}...{head}"))
            .await
    }

    /// 更新分支引用（`PATCH /repos/{o}/{r}/git/refs/heads/{branch}`）。
    ///
    /// `force = true` 时即使不是快进也强制移动 —— 对应同步方案里的「覆盖」模式，
    /// 会丢弃目标分支的独有提交，调用方必须二次确认。
    pub async fn update_ref(
        &self,
        owner: &str,
        repo: &str,
        branch: &str,
        sha: &str,
        force: bool,
    ) -> Result<String> {
        let body = serde_json::json!({ "sha": sha, "force": force });
        self.client
            .patch_json(&format!("/repos/{owner}/{repo}/git/refs/heads/{branch}"), &body.to_string())
            .await
    }

    /// 创建分支（POST /repos/{o}/{r}/git/refs）
    pub async fn create_branch(
        &self,
        owner: &str,
        repo: &str,
        branch: &str,
        sha: &str,
    ) -> Result<String> {
        let body = serde_json::json!({
            "ref": format!("refs/heads/{branch}"),
            "sha": sha,
        });
        let path = format!("/repos/{owner}/{repo}/git/refs");
        self.client.post_json(&path, &body.to_string()).await
    }

    /// 创建 Pull Request（POST /repos/{o}/{r}/pulls）
    pub async fn create_pull_request(
        &self,
        owner: &str,
        repo: &str,
        title: &str,
        body_text: &str,
        head: &str,
        base: &str,
        draft: bool,
    ) -> Result<String> {
        let body = serde_json::json!({
            "title": title,
            "body": body_text,
            "head": head,
            "base": base,
            "draft": draft,
        });
        let path = format!("/repos/{owner}/{repo}/pulls");
        self.client.post_json(&path, &body.to_string()).await
    }

    /// 合并 Pull Request（PUT /repos/{o}/{r}/pulls/{n}/merge）
    /// merge_method: merge / squash / rebase
    pub async fn merge_pull_request(
        &self,
        owner: &str,
        repo: &str,
        number: u64,
        merge_method: &str,
    ) -> Result<String> {
        let body = serde_json::json!({ "merge_method": merge_method });
        let path = format!("/repos/{owner}/{repo}/pulls/{number}/merge");
        self.client.put_json(&path, &body.to_string()).await
    }

    /// 删除远端分支（DELETE /repos/{o}/{r}/git/refs/heads/{branch}）
    pub async fn delete_branch(&self, owner: &str, repo: &str, branch: &str) -> Result<String> {
        let path = format!("/repos/{owner}/{repo}/git/refs/heads/{branch}");
        self.client.delete_json(&path).await
    }

    /// 修改仓库默认分支（PATCH /repos/{o}/{r}）
    pub async fn update_default_branch(
        &self,
        owner: &str,
        repo: &str,
        branch: &str,
    ) -> Result<String> {
        let body = serde_json::json!({ "default_branch": branch });
        let path = format!("/repos/{owner}/{repo}");
        self.client.patch_json(&path, &body.to_string()).await
    }

    /// 删除仓库（DELETE /repos/{o}/{r}）
    pub async fn delete_repo(&self, owner: &str, repo: &str) -> Result<String> {
        let path = format!("/repos/{owner}/{repo}");
        self.client.delete_json(&path).await
    }

    /// 更新当前登录用户资料（PATCH /user，body 为 JSON 字符串）
    pub async fn update_profile(&self, body: &str) -> Result<String> {
        self.client.patch_json("/user", body).await
    }

    /// 手动触发工作流（`POST /repos/{o}/{r}/actions/workflows/{id}/dispatches`）。
    ///
    /// - `git_ref`：分支或标签名（必须是仓库里已存在的 ref）；
    /// - `inputs_json`：输入参数 JSON 对象字符串，如 `{"version":"1.0.13"}`；非法/空串按 `{}` 处理。
    ///
    /// 成功时 GitHub 返回 **204 No Content**（body 为空串）；工作流未声明
    /// `workflow_dispatch` 时返回 422，错误信息由调用方透出。
    pub async fn dispatch_workflow(
        &self,
        owner: &str,
        repo: &str,
        workflow_id: u64,
        git_ref: &str,
        inputs_json: &str,
    ) -> Result<String> {
        let inputs: serde_json::Value =
            serde_json::from_str(inputs_json).unwrap_or_else(|_| serde_json::json!({}));
        let body = serde_json::json!({ "ref": git_ref, "inputs": inputs });
        let path = format!("/repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches");
        self.client.post_json(&path, &body.to_string()).await
    }
}
