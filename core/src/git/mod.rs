//! Git 工具包：基于 libgit2（`git2` crate），提供 clone / pull（fast-forward）。
//!
//! 对齐 `docs/code-editing-collaboration-thinking.md` §9：Git 引擎（libgit2）+ 稳定接口。
//! HTTPS 走 vendored OpenSSL（见 Cargo.toml）。

use crate::error::{CoreError, Result};

/// 浅 clone 仓库到本地目录。
///
/// - `url`：仓库 HTTPS URL（如 `https://github.com/o/r.git`）。
/// - `into`：本地目标目录（绝对路径）。
/// - `branch`：要检出的分支（`None` = 默认分支）。
/// - `token`：可选的 PAT（私有仓库用；`None` = 匿名）。
pub fn clone_repo(url: &str, into: &str, branch: Option<&str>, token: Option<&str>) -> Result<()> {
    use git2::build::RepoBuilder;
    use git2::{FetchOptions, RemoteCallbacks};

    let mut callbacks = RemoteCallbacks::new();
    if let Some(tk) = token {
        let tk = tk.to_string();
        callbacks.credentials(move |_url, username, _allowed| {
            let user = username.unwrap_or("x-access-token");
            git2::Cred::userpass_plaintext(user, &tk)
        });
    } else {
        callbacks.credentials(|_url, _username, _allowed| git2::Cred::default());
    }

    let mut fo = FetchOptions::new();
    fo.remote_callbacks(callbacks).depth(1); // 浅 clone，减体积

    let mut builder = RepoBuilder::new();
    builder.fetch_options(fo);
    if let Some(b) = branch {
        builder.branch(b);
    }

    builder
        .clone(url, std::path::Path::new(into))
        .map_err(|e| CoreError::Other(format!("clone 失败: {e}")))?;
    Ok(())
}

/// pull：fetch origin 并 fast-forward 当前分支到远端。
pub fn pull_repo(dir: &str, token: Option<&str>) -> Result<()> {
    use git2::{FetchOptions, RemoteCallbacks, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut remote = repo
        .find_remote("origin")
        .map_err(|e| CoreError::Other(format!("找不到 origin: {e}")))?;

    let mut callbacks = RemoteCallbacks::new();
    if let Some(tk) = token {
        let tk = tk.to_string();
        callbacks.credentials(move |_url, username, _allowed| {
            let user = username.unwrap_or("x-access-token");
            git2::Cred::userpass_plaintext(user, &tk)
        });
    }

    let mut fo = FetchOptions::new();
    fo.remote_callbacks(callbacks);
    remote
        .fetch(&["refs/heads/*:refs/remotes/origin/*"], Some(&mut fo), None)
        .map_err(|e| CoreError::Other(format!("fetch 失败: {e}")))?;

    // 当前分支名
    let head = repo.head().map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?;
    let branch_short = head
        .shorthand()
        .ok_or_else(|| CoreError::Other("无法获取分支名".into()))?
        .to_string();
    let head_name = head
        .name()
        .ok_or_else(|| CoreError::Other("无法获取 ref 名".into()))?
        .to_string();

    // 远端跟踪分支
    let remote_ref_name = format!("refs/remotes/origin/{branch_short}");
    let remote_ref = match repo.find_reference(&remote_ref_name) {
        Ok(r) => r,
        Err(_) => return Ok(()), // 无远端跟踪分支，跳过合并
    };
    let remote_commit = repo
        .reference_to_annotated_commit(&remote_ref)
        .map_err(|e| CoreError::Other(format!("解析远端提交失败: {e}")))?;

    let (analysis, _) = repo
        .merge_analysis(&[&remote_commit])
        .map_err(|e| CoreError::Other(format!("merge 分析失败: {e}")))?;

    if analysis.is_fast_forward() {
        let mut head_ref = repo
            .find_reference(&head_name)
            .map_err(|e| CoreError::Other(format!("读取分支 ref 失败: {e}")))?;
        head_ref
            .set_target(remote_commit.id(), "fast-forward")
            .map_err(|e| CoreError::Other(format!("fast-forward 失败: {e}")))?;
        repo.set_head(&head_name)
            .map_err(|e| CoreError::Other(format!("set_head 失败: {e}")))?;
        repo.checkout_head(Some(git2::build::CheckoutBuilder::default().force()))
            .map_err(|e| CoreError::Other(format!("checkout 失败: {e}")))?;
    } else if !analysis.is_up_to_date() {
        // 本地有未推送提交 + 远端领先 → 分叉（对齐 P0-1 分叉决策页触发条件）
        return Err(CoreError::Other("nff: 本地与远端分叉（non-fast-forward）".into()));
    }
    Ok(())
}

/// 本地 git commit：暂存所有改动 + 提交（返回 commit sha）
pub fn commit_repo(dir: &str, message: &str, author_name: &str, author_email: &str) -> Result<String> {
    use git2::{Repository, Signature};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut index = repo.index().map_err(|e| CoreError::Other(format!("读取索引失败: {e}")))?;
    index
        .add_all(["*"].iter(), git2::IndexAddOption::DEFAULT, None)
        .map_err(|e| CoreError::Other(format!("暂存失败: {e}")))?;
    index.write().map_err(|e| CoreError::Other(format!("写索引失败: {e}")))?;

    let tree_id = index.write_tree().map_err(|e| CoreError::Other(format!("写树失败: {e}")))?;
    let tree = repo.find_tree(tree_id).map_err(|e| CoreError::Other(format!("找树失败: {e}")))?;
    let sig = Signature::now(author_name, author_email)
        .map_err(|e| CoreError::Other(format!("签名失败: {e}")))?;

    let parent = repo
        .head()
        .ok()
        .and_then(|h| h.target())
        .and_then(|t| repo.find_commit(t).ok());
    let parents: Vec<&git2::Commit> = parent.iter().collect();

    let id = repo
        .commit(Some("HEAD"), &sig, &sig, message, &tree, &parents)
        .map_err(|e| CoreError::Other(format!("commit 失败: {e}")))?;
    Ok(id.to_string())
}

/// 本地 git push：推送到 origin
pub fn push_repo(dir: &str, token: Option<&str>, branch: &str) -> Result<()> {
    use git2::{PushOptions, RemoteCallbacks, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut remote = repo
        .find_remote("origin")
        .map_err(|e| CoreError::Other(format!("找不到 origin: {e}")))?;

    let mut callbacks = RemoteCallbacks::new();
    if let Some(tk) = token {
        let tk = tk.to_string();
        callbacks.credentials(move |_url, username, _allowed| {
            let user = username.unwrap_or("x-access-token");
            git2::Cred::userpass_plaintext(user, &tk)
        });
    }

    let mut opts = PushOptions::new();
    opts.remote_callbacks(callbacks);
    let refspec = format!("refs/heads/{branch}:refs/heads/{branch}");
    remote
        .push(&[&refspec], Some(&mut opts))
        .map_err(|e| CoreError::Other(format!("push 失败: {e}")))?;
    Ok(())
}
// ── 决策页面支持 API（对齐 docs/decision-pages-gap.md §6） ──

use serde_json::json;

/// 仓库状态（JSON）：branch / ahead / behind / has_upstream / remote_url / dirty / unpushed。
/// 供分叉决策、Git 化回退、删除升级警告、撤销误提交等决策页面读取事实区。
pub fn repo_status(dir: &str) -> Result<String> {
    use git2::{Repository, StatusOptions};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;

    let head = repo.head().map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?;
    let branch = head.shorthand().unwrap_or("").to_string();
    let local_commit = head
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("解析 HEAD 提交失败: {e}")))?;

    // 上游与领先/落后计数
    let mut ahead: usize = 0;
    let mut behind: usize = 0;
    let mut has_upstream = false;
    let mut remote_oid: Option<git2::Oid> = None;
    if let Ok(local_branch) = repo.find_branch(&branch, git2::BranchType::Local) {
        if let Ok(upstream) = local_branch.upstream() {
            has_upstream = true;
            if let (Some(local_oid), Some(up_oid)) = (local_commit.id().into(), upstream.get().target()) {
                let (a, b) = repo
                    .graph_ahead_behind(local_oid, up_oid)
                    .map_err(|e| CoreError::Other(format!("计算领先落后失败: {e}")))?;
                ahead = a;
                behind = b;
                remote_oid = Some(up_oid);
            }
        }
    }

    // 远端 URL
    let remote_url = repo
        .find_remote("origin")
        .ok()
        .and_then(|r| r.url().map(|s| s.to_string()))
        .unwrap_or_default();

    // dirty 文件清单
    let mut dirty = Vec::new();
    let mut opts = StatusOptions::new();
    opts.include_untracked(true);
    let statuses = repo
        .statuses(Some(&mut opts))
        .map_err(|e| CoreError::Other(format!("读取工作区状态失败: {e}")))?;
    for entry in statuses.iter() {
        let s = entry.status();
        let st = if s.is_index_new() || s.is_wt_new() {
            "A"
        } else if s.is_index_deleted() || s.is_wt_deleted() {
            "D"
        } else {
            "M"
        };
        if let Some(p) = entry.path() {
            dirty.push(json!({ "path": p, "status": st }));
        }
    }

    // 未推送提交清单（HEAD..上游，最多 50 条）
    let mut unpushed = Vec::new();
    if let Some(up_oid) = remote_oid {
        let mut revwalk = repo
            .revwalk()
            .map_err(|e| CoreError::Other(format!("创建 revwalk 失败: {e}")))?;
        revwalk.push_head().map_err(|e| CoreError::Other(format!("revwalk push_head 失败: {e}")))?;
        revwalk.hide(up_oid).map_err(|e| CoreError::Other(format!("revwalk hide 失败: {e}")))?;
        for oid in revwalk.take(50) {
            let oid = oid.map_err(|e| CoreError::Other(format!("revwalk 迭代失败: {e}")))?;
            if let Ok(c) = repo.find_commit(oid) {
                unpushed.push(json!({
                    "sha": &c.id().to_string()[..7],
                    "message": c.summary().unwrap_or("")
                }));
            }
        }
    }

    Ok(json!({
        "branch": branch,
        "ahead": ahead,
        "behind": behind,
        "has_upstream": has_upstream,
        "remote_url": remote_url,
        "dirty": dirty,
        "unpushed": unpushed
    })
    .to_string())
}

/// 撤销最近一次提交但保留改动（reset --soft HEAD~1）。
pub fn reset_soft(dir: &str) -> Result<()> {
    use git2::{Repository, ResetType};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let parent_oid = repo
        .head()
        .map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("解析 HEAD 失败: {e}")))?
        .parent_id(0)
        .map_err(|e| CoreError::Other(format!("没有父提交可撤销: {e}")))?;
    let obj = repo
        .find_object(parent_oid, None)
        .map_err(|e| CoreError::Other(format!("找对象失败: {e}")))?;
    repo.reset(&obj, ResetType::Soft, None)
        .map_err(|e| CoreError::Other(format!("reset --soft 失败: {e}")))?;
    Ok(())
}

/// 放弃本地提交：reset --hard origin/{branch}（危险，需 UI 二次确认）。
pub fn reset_hard_to_remote(dir: &str, branch: &str) -> Result<()> {
    use git2::{Repository, ResetType};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let remote_ref_name = format!("refs/remotes/origin/{branch}");
    let remote_ref = repo
        .find_reference(&remote_ref_name)
        .map_err(|e| CoreError::Other(format!("找不到 {remote_ref_name}: {e}")))?;
    let oid = remote_ref
        .target()
        .ok_or_else(|| CoreError::Other("远端引用无目标".into()))?;
    let obj = repo
        .find_object(oid, None)
        .map_err(|e| CoreError::Other(format!("找对象失败: {e}")))?;
    repo.reset(&obj, ResetType::Hard, None)
        .map_err(|e| CoreError::Other(format!("reset --hard 失败: {e}")))?;
    Ok(())
}

/// 修改最近一次提交信息（amend，未推送提交专用）。
pub fn amend_message(dir: &str, new_message: &str) -> Result<()> {
    use git2::{Repository, Signature};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let head = repo.head().map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?;
    let commit = head
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("解析 HEAD 失败: {e}")))?;
    let tree = commit.tree().map_err(|e| CoreError::Other(format!("读取树失败: {e}")))?;
    let parents: Vec<git2::Commit> = commit.parents().collect();
    let parent_refs: Vec<&git2::Commit> = parents.iter().collect();

    let author = commit.author();
    let committer = commit.committer();
    let sig_author = Signature::new(
        author.name().unwrap_or(""),
        author.email().unwrap_or(""),
        &author.when(),
    )
    .map_err(|e| CoreError::Other(format!("构造作者签名失败: {e}")))?;
    let sig_committer = Signature::new(
        committer.name().unwrap_or(""),
        committer.email().unwrap_or(""),
        &committer.when(),
    )
    .map_err(|e| CoreError::Other(format!("构造提交者签名失败: {e}")))?;

    let head_name = head
        .name()
        .ok_or_else(|| CoreError::Other("无法获取 ref 名".into()))?
        .to_string();
    repo.commit(
        Some(&head_name),
        &sig_author,
        &sig_committer,
        new_message,
        &tree,
        &parent_refs,
    )
    .map_err(|e| CoreError::Other(format!("amend 失败: {e}")))?;
    Ok(())
}

/// 对已推送提交创建 revert 提交（不改写历史，对齐 D11 边界）。
/// 返回新提交 sha。
pub fn revert_commit(
    dir: &str,
    sha: &str,
    message: &str,
    author_name: &str,
    author_email: &str,
) -> Result<String> {
    use git2::{Repository, Signature};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let obj = repo
        .revparse_single(sha)
        .map_err(|e| CoreError::Other(format!("解析提交 {sha} 失败: {e}")))?;
    let commit = obj
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("目标不是提交: {e}")))?;

    // 工作区必须干净才能 revert
    let mut st_opts = git2::StatusOptions::new();
    st_opts.include_untracked(false);
    let statuses = repo
        .statuses(Some(&mut st_opts))
        .map_err(|e| CoreError::Other(format!("读取工作区状态失败: {e}")))?;
    if !statuses.is_empty() {
        return Err(CoreError::Other("工作区有未提交改动，无法 revert".into()));
    }

    // git2 0.18：revert 直接应用到工作区与索引，返回 ()；随后从索引取树
    repo.revert(&commit, None)
        .map_err(|e| CoreError::Other(format!("revert 失败: {e}")))?;
    let mut idx = repo
        .index()
        .map_err(|e| CoreError::Other(format!("读取索引失败: {e}")))?;
    let tree_id = idx
        .write_tree()
        .map_err(|e| CoreError::Other(format!("写树失败: {e}")))?;
    let tree = repo.find_tree(tree_id).map_err(|e| CoreError::Other(format!("找树失败: {e}")))?;
    let sig = Signature::now(author_name, author_email)
        .map_err(|e| CoreError::Other(format!("签名失败: {e}")))?;
    let parent = repo
        .head()
        .ok()
        .and_then(|h| h.target())
        .and_then(|t| repo.find_commit(t).ok());
    let parents: Vec<&git2::Commit> = parent.iter().collect();
    let id = repo
        .commit(Some("HEAD"), &sig, &sig, message, &tree, &parents)
        .map_err(|e| CoreError::Other(format!("revert 提交失败: {e}")))?;
    Ok(id.to_string())
}

/// 首次 push：确保 origin 远端 + 推送 + 设置上游分支（对齐 P2-2）。
pub fn push_set_upstream(
    dir: &str,
    remote_url: &str,
    branch: &str,
    token: Option<&str>,
) -> Result<()> {
    use git2::{BranchType, PushOptions, RemoteCallbacks, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut remote = match repo.find_remote("origin") {
        Ok(r) => r,
        Err(_) => repo
            .remote("origin", remote_url)
            .map_err(|e| CoreError::Other(format!("添加 origin 失败: {e}")))?,
    };
    if remote.url() != Some(remote_url) {
        repo.remote_set_url("origin", remote_url)
            .map_err(|e| CoreError::Other(format!("更新 origin URL 失败: {e}")))?;
    }

    let mut callbacks = RemoteCallbacks::new();
    if let Some(tk) = token {
        let tk = tk.to_string();
        callbacks.credentials(move |_url, username, _allowed| {
            let user = username.unwrap_or("x-access-token");
            git2::Cred::userpass_plaintext(user, &tk)
        });
    }

    let mut opts = PushOptions::new();
    opts.remote_callbacks(callbacks);
    let refspec = format!("refs/heads/{branch}:refs/heads/{branch}");
    remote
        .push(&[&refspec], Some(&mut opts))
        .map_err(|e| {
            let s = e.to_string();
            if s.contains("non-fast-forward") || s.contains("cannot lock ref") || s.contains("rejected") {
                CoreError::Other(format!("nff: 推送被拒（远端领先）: {s}"))
            } else {
                CoreError::Other(format!("push 失败: {s}"))
            }
        })?;

    let mut local_branch = repo
        .find_branch(branch, BranchType::Local)
        .map_err(|e| CoreError::Other(format!("找不到本地分支 {branch}: {e}")))?;
    local_branch
        .set_upstream(Some(&format!("origin/{branch}")))
        .map_err(|e| CoreError::Other(format!("设置上游失败: {e}")))?;
    Ok(())
}

/// 敏感信息本地扫描（提交前警告，对齐 P0-4）。
/// 返回 JSON 数组：[{line, kind, mask}]；完整值仅取前 4 字符打码，绝不上传。
pub fn scan_sensitive(text: &str) -> Result<String> {
    let mut hits = Vec::new();

    // 逐行扫描（行号从 1 开始）
    for (idx, line) in text.lines().enumerate() {
        let line_no = idx + 1;
        let mut detect = |prefix: &str, kind: &str, min_len: usize| {
            if let Some(pos) = line.find(prefix) {
                let rest = &line[pos + prefix.len()..];
                let token: String = rest
                    .chars()
                    .take_while(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
                    .collect();
                if token.len() >= min_len {
                    let mask = format!("{}****", &token.chars().take(4).collect::<String>());
                    hits.push(json!({ "line": line_no, "kind": kind, "mask": mask }));
                }
            }
        };
        detect("ghp_", "GitHub PAT", 20);
        detect("gho_", "GitHub OAuth", 20);
        detect("ghu_", "GitHub User Token", 20);
        detect("ghs_", "GitHub Server Token", 20);
        detect("ghr_", "GitHub Refresh Token", 20);
        detect("AKIA", "AWS Access Key", 16);
        detect("xoxb-", "Slack Bot Token", 10);
        detect("xoxp-", "Slack User Token", 10);
        detect("sk-", "API Key", 20);

        // 私钥块起始
        if line.starts_with("-----BEGIN") && line.contains("PRIVATE KEY") {
            hits.push(json!({ "line": line_no, "kind": "私钥", "mask": "-----BEGIN****" }));
        }

        // key=value 通用模式（api_key / secret / password / token = ...）
        let lower = line.to_lowercase();
        for kw in ["api_key", "apikey", "secret", "password", "passwd", "token"] {
            if lower.contains(kw) {
                if let Some(eq) = line.find('=') {
                    let val = line[eq + 1..].trim().trim_matches('"').trim_matches('\'');
                    if val.len() >= 8 && !val.contains(' ') {
                        let mask = format!("{}****", &val.chars().take(4).collect::<String>());
                        hits.push(json!({ "line": line_no, "kind": format!("疑似{kw}"), "mask": mask }));
                    }
                }
            }
        }
    }

    Ok(serde_json::to_string(&hits).map_err(|e| CoreError::Json(e))?)
}
